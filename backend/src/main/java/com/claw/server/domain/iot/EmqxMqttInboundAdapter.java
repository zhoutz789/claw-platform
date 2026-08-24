package com.claw.server.domain.iot;

import com.claw.server.common.dto.IoTRequests;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
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
import java.util.UUID;

/**
 * EMQX MQTT 遥测接入适配器（B3）：真实订阅 EMQX Broker，将设备上报的遥测帧
 * 解析后写入 IoTService（与现有 REST 上报路径共用同一持久化逻辑）。
 *
 * <p>仅当 {@code claw.iot.emqx.enabled=true} 时激活（本地/测试默认关闭，仍走 REST 上报）；
 * 连接参数全部由环境变量注入：
 * <ul>
 *   <li>{@code CLAW_IOT_EMQX_BROKER_URL} — tcp://host:1883</li>
 *   <li>{@code CLAW_IOT_EMQX_USERNAME} / {@code CLAW_IOT_EMQX_PASSWORD}</li>
 *   <li>{@code CLAW_IOT_EMQX_TOPIC} — 订阅主题，默认 {@code claw/telemetry/+}</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "claw.iot.emqx.enabled", havingValue = "true")
@Slf4j
public class EmqxMqttInboundAdapter implements MqttCallback {

    private final IoTService iotService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${claw.iot.emqx.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${claw.iot.emqx.username:}")
    private String username;

    @Value("${claw.iot.emqx.password:}")
    private String password;

    @Value("${claw.iot.emqx.topic:claw/telemetry/+}")
    private String topic;

    @Value("${claw.iot.emqx.client-id:claw-emqx-inbound}")
    private String clientId;

    private MqttClient client;

    public EmqxMqttInboundAdapter(IoTService iotService) {
        this.iotService = iotService;
    }

    @PostConstruct
    public void connect() throws MqttException {
        client = new MqttClient(brokerUrl, clientId + "-" + UUID.randomUUID());
        MqttConnectOptions opts = new MqttConnectOptions();
        if (username != null && !username.isBlank()) {
            opts.setUserName(username);
            opts.setPassword(password == null ? new char[0] : password.toCharArray());
        }
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        client.setCallback(this);
        client.connect(opts);
        client.subscribe(topic);
        log.info("[EMQX] 已连接 {} 并订阅主题 {}", brokerUrl, topic);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            IoTRequests.TelemetryReport req = new IoTRequests.TelemetryReport(
                    node.get("imei").asText(),
                    big(node, "speed"),
                    big(node, "soc"),
                    big(node, "temp"),
                    big(node, "humid"),
                    node.hasNonNull("faults") ? node.get("faults").asText() : null,
                    big(node, "lat"),
                    big(node, "lng"));
            iotService.reportTelemetry(req);
        } catch (Exception e) {
            log.error("[EMQX] 解析/落库遥测失败 topic={}", topic, e);
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
            if (client != null && client.isConnected()) {
                client.disconnect();
                client.close();
            }
            log.info("[EMQX] 已断开订阅");
        } catch (MqttException e) {
            log.warn("[EMQX] 断开异常", e);
        }
    }

    private BigDecimal big(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).decimalValue() : null;
    }
}
