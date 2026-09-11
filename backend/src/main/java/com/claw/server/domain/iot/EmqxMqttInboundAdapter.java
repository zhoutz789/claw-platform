package com.claw.server.domain.iot;

import com.claw.server.common.dto.IoTRequests;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * EMQX MQTT 接入适配器（B3 入站半边）：真实订阅 EMQX Broker。
 *
 * <p>订阅两类主题：
 * <ul>
 *   <li>{@code claw/iot/#}  —— 车辆终端契约上行（按 msgType 分发）：
 *        location 定位 / status 状态 / cmd_ack 指令回执</li>
 *   <li>{@code claw/telemetry/+} —— 老 BMS 链路（imei 帧，保持兼容）</li>
 * </ul>
 * 仅当 {@code claw.iot.emqx.enabled=true} 时激活；连接参数全部由环境变量注入。
 */
@Component
@ConditionalOnProperty(name = "claw.iot.emqx.enabled", havingValue = "true")
@Slf4j
public class EmqxMqttInboundAdapter implements MqttCallbackExtended {

    private final IoTService iotService;
    private final DeviceCommandService deviceCommandService;
    private final BmsTelemetryService bmsTelemetryService;
    private final BmsAdapter bmsAdapter;
    private final PvTelemetryService pvTelemetryService;
    private final PvAdapter pvAdapter;
    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Value("${claw.iot.emqx.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${claw.iot.emqx.username:}")
    private String username;

    @Value("${claw.iot.emqx.password:}")
    private String password;

    @Value("${claw.iot.emqx.topic:claw/telemetry/+}")
    private String legacyTopic;

    @Value("${claw.iot.emqx.client-id:claw-emqx-inbound}")
    private String clientId;

    @Value("${claw.iot.emqx.ssl.enabled:false}")
    private boolean sslEnabled;
    @Value("${claw.iot.emqx.ssl.truststore-path:}")
    private String trustStorePath;
    @Value("${claw.iot.emqx.ssl.truststore-pass:}")
    private String trustStorePass;
    @Value("${claw.iot.emqx.ssl.keystore-path:}")
    private String keyStorePath;
    @Value("${claw.iot.emqx.ssl.keystore-pass:}")
    private String keyStorePass;

    private MqttClient client;

    /** 后台重试调度器：初始/断线后定时重连并重订阅，避免 EMQX 暂不可达时拖垮后端启动。 */
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "emqx-inbound-retry");
                t.setDaemon(true);
                return t;
            });

    public EmqxMqttInboundAdapter(IoTService iotService, DeviceCommandService deviceCommandService,
                                 BmsTelemetryService bmsTelemetryService, BmsAdapter bmsAdapter,
                                 PvTelemetryService pvTelemetryService, PvAdapter pvAdapter,
                                 DeviceRepository deviceRepository) {
        this.iotService = iotService;
        this.deviceCommandService = deviceCommandService;
        this.bmsTelemetryService = bmsTelemetryService;
        this.bmsAdapter = bmsAdapter;
        this.pvTelemetryService = pvTelemetryService;
        this.pvAdapter = pvAdapter;
        this.deviceRepository = deviceRepository;
    }

    @PostConstruct
    public void init() {
        tryConnect();
    }

    /** 尝试连接；失败仅记日志并在 10s 后重试，不抛出以中止 Spring 上下文。 */
    private void tryConnect() {
        try {
            doConnect();
        } catch (Exception e) {
            log.error("[EMQX] 连接失败（{}），10s 后重试", e.getMessage());
            retryScheduler.schedule(this::tryConnect, 10, TimeUnit.SECONDS);
        }
    }

    private void doConnect() throws MqttException {
        client = new MqttClient(brokerUrl, clientId + "-" + UUID.randomUUID());
        MqttConnectOptions opts = new MqttConnectOptions();
        if (username != null && !username.isBlank()) {
            opts.setUserName(username);
            opts.setPassword(password == null ? new char[0] : password.toCharArray());
        }
        if (sslEnabled && brokerUrl.startsWith("ssl://") && trustStorePath != null && !trustStorePath.isBlank()) {
            try {
                opts.setSocketFactory(MqttSslHelper.build(trustStorePath, trustStorePass, keyStorePath, keyStorePass));
            } catch (Exception e) {
                throw new IllegalStateException("构建 MQTT SSL 工厂失败", e);
            }
        }
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        client.setCallback(this);
        client.connect(opts);
        subscribeTopics();
    }

    /** 订阅上行主题；连接成功与自动重连后都会调用，保证订阅不丢。 */
    private void subscribeTopics() throws MqttException {
        client.subscribe(legacyTopic);          // 老 BMS 链路
        client.subscribe("claw/iot/#");         // 车辆终端契约上行
        log.info("[EMQX] 已连接 {} 并订阅 {} 与 claw/iot/#", brokerUrl, legacyTopic);
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            subscribeTopics();
        } catch (MqttException e) {
            log.warn("[EMQX] 重连后重新订阅失败", e);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());

            if (topic.startsWith("claw/iot/") && topic.endsWith("/up")) {
                String msgType = node.hasNonNull("msgType") ? node.get("msgType").asText() : "";
                switch (msgType) {
                    case "location" ->
                            iotService.reportLocation(objectMapper.treeToValue(node, IoTRequests.VehicleLocation.class));
                    case "status" ->
                            iotService.reportStatus(objectMapper.treeToValue(node, IoTRequests.VehicleStatus.class));
                    case "cmd_ack" ->
                            deviceCommandService.handleAck(objectMapper.treeToValue(node, IoTRequests.CommandAck.class));
                    default -> log.warn("[EMQX] 未知 msgType={} topic={}", msgType, topic);
                }
            } else if (topic.startsWith("claw/iot/") && topic.endsWith("/telemetry")) {
                // 规范遥测上行：同一 topic 同时承载锂电池 BMS 与光伏两类设备，
                // 必须按设备的 device_type 分流，否则光伏报文会被静默写进 BMS 列组。
                String deviceNo = extractDeviceNo(topic);
                if (resolveTelemetryHandler(node, deviceNo) == TelemetryKind.PV) {
                    com.claw.server.common.dto.PvTelemetryReport report =
                            pvAdapter.normalize(node, PvAdapter.Profile.GENERIC_MQTT);
                    pvTelemetryService.handleReport(report, deviceNo);
                } else {
                    com.claw.server.common.dto.BmsTelemetryReport report =
                            bmsAdapter.normalize(node, BmsAdapter.Profile.GENERIC_MQTT);
                    bmsTelemetryService.handleReport(report, deviceNo);
                }
            } else {
                // 老 BMS 链路：imei 帧（保持兼容）
                IoTRequests.TelemetryReport req = new IoTRequests.TelemetryReport(
                        node.get("imei").asText(),
                        big(node, "speed"),
                        big(node, "soc"),
                        big(node, "temp"),
                        big(node, "humid"),
                        node.hasNonNull("faults") ? node.get("faults").asText() : null,
                        big(node, "lat"),
                        big(node, "lng"),
                        big(node, "soh"));
                iotService.reportTelemetry(req);
            }
        } catch (Exception e) {
            log.error("[EMQX] 解析/落库失败 topic={}", topic, e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("[EMQX] 连接断开，等待自动重连", cause);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // 仅订阅侧，无需处理
    }

    @PreDestroy
    public void disconnect() {
        try {
            retryScheduler.shutdownNow();
            if (client != null && client.isConnected()) {
                client.disconnect();
                client.close();
            }
            log.info("[EMQX] 已断开订阅");
        } catch (MqttException e) {
            log.warn("[EMQX] 断开异常", e);
        }
    }

    /**
     * 判定遥测报文归属链路。
     *
     * <p><b>查设备的键</b>：优先取报文体 {@code deviceNo}，缺失/空白时回退到 topic
     * 路径 {@code claw/iot/{deviceNo}/telemetry} 的第 3 段（与
     * {@code BmsTelemetryService#handleReport} 的 topicDeviceNo 兜底同口径）。
     *
     * <p><b>分流规则</b>：按 {@code devices.device_type} ——
     * INVERTER / PV_METER / WEATHER_STATION / PV_GATEWAY 走光伏；BATTERY_BMS 走 BMS。
     *
     * <p><b>回落</b>：设备查不到、deviceNo 缺失、deviceType 为空或为其它未知类型时，
     * 一律<b>回落 BMS 老链路</b>（保兼容，避免未建档/老设备报文被丢弃）并打 warn 日志说明原因。
     */
    private TelemetryKind resolveTelemetryHandler(JsonNode node, String topicDeviceNo) {
        String deviceNo = topicDeviceNo;
        if (node.hasNonNull("deviceNo")) {
            String inBody = node.get("deviceNo").asText();
            if (inBody != null && !inBody.isBlank()) {
                deviceNo = inBody;
            }
        }
        if (deviceNo == null || deviceNo.isBlank()) {
            log.warn("[EMQX] 遥测报文缺 deviceNo（topic={}），回落 BMS 链路", topicDeviceNo);
            return TelemetryKind.BMS;
        }
        Optional<Device> device = deviceRepository.findByDeviceNo(deviceNo);
        if (device.isEmpty()) {
            log.warn("[EMQX] 遥测设备未建档 deviceNo={}，回落 BMS 链路", deviceNo);
            return TelemetryKind.BMS;
        }
        String type = device.get().getDeviceType();
        if (type == null || type.isBlank()) {
            log.warn("[EMQX] 遥测设备 deviceNo={} 的 device_type 为空，回落 BMS 链路", deviceNo);
            return TelemetryKind.BMS;
        }
        return switch (type) {
            case "INVERTER", "PV_METER", "WEATHER_STATION", "PV_GATEWAY" -> TelemetryKind.PV;
            case "BATTERY_BMS" -> TelemetryKind.BMS;
            default -> {
                log.warn("[EMQX] 遥测设备 deviceNo={} 的 device_type={} 无对应链路，回落 BMS 链路", deviceNo, type);
                yield TelemetryKind.BMS;
            }
        };
    }

    /** 遥测链路归属。 */
    private enum TelemetryKind {
        PV,
        BMS
    }

    private BigDecimal big(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).decimalValue() : null;
    }

    /** 从 {@code claw/iot/{deviceNo}/telemetry} 主题提取设备编号（报文缺失时的兜底）。 */
    private String extractDeviceNo(String topic) {
        String[] parts = topic.split("/");
        // ["claw", "iot", "{deviceNo}", "telemetry"]
        return parts.length >= 3 ? parts[2] : null;
    }
}
