package com.claw.server.domain.iot;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * EMQX 下行指令网关（B3 出站半边）：连接 Broker 并向 {@code claw/iot/{deviceNo}/down} 发布指令。
 *
 * <p>仅在 {@code claw.iot.emqx.enabled=true} 时激活（与入站适配器同开关）。
 * 连接参数由环境变量注入，与入站共用同一 Broker。
 */
@Component
@ConditionalOnProperty(name = "claw.iot.emqx.enabled", havingValue = "true")
@Slf4j
public class EmqxMqttCommandGateway {

    @Value("${claw.iot.emqx.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${claw.iot.emqx.username:}")
    private String username;

    @Value("${claw.iot.emqx.password:}")
    private String password;

    @Value("${claw.iot.emqx.client-id:claw-emqx-outbound}")
    private String clientId;

    /** 下行主题模板，%s 替换为 deviceNo。 */
    @Value("${claw.iot.emqx.down-topic:claw/iot/%s/down}")
    private String downTopicTemplate;

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

    /** 后台重试调度器：初始/断线后定时重连，避免 EMQX 暂不可达时拖垮整个后端启动。 */
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "emqx-outbound-retry");
                t.setDaemon(true);
                return t;
            });

    @PostConstruct
    public void init() {
        tryConnect();
    }

    /** 尝试连接；失败仅记日志并在 10s 后重试，不抛出以中止 Spring 上下文。 */
    private void tryConnect() {
        try {
            doConnect();
        } catch (Exception e) {
            log.error("[EMQX-OUT] 连接失败（{}），10s 后重试", e.getMessage());
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
        client.connect(opts);
        log.info("[EMQX-OUT] 已连接 {} 用于下行指令发布 (ssl={})", brokerUrl, sslEnabled);
    }

    /** 向指定设备下发指令报文（JSON）。 */
    public void publish(String deviceNo, String json) {
        try {
            ensureConnected();
            String topic = downTopicTemplate.replace("%s", deviceNo);
            MqttMessage msg = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
            msg.setQos(1);
            client.publish(topic, msg);
            log.info("[EMQX-OUT] 已下发指令 deviceNo={} topic={}", deviceNo, topic);
        } catch (MqttException e) {
            log.error("[EMQX-OUT] 下发失败 deviceNo={}", deviceNo, e);
            throw new IllegalStateException("MQTT 指令下发失败", e);
        }
    }

    /** 发布前确保已连接；未连接则同步重试一次，仍失败则抛出异常。 */
    private void ensureConnected() throws MqttException {
        if (client != null && client.isConnected()) {
            return;
        }
        try {
            doConnect();
        } catch (MqttException e) {
            log.warn("[EMQX-OUT] 按需重连失败", e);
            throw e;
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            retryScheduler.shutdownNow();
            if (client != null && client.isConnected()) {
                client.disconnect();
                client.close();
            }
            log.info("[EMQX-OUT] 已断开");
        } catch (MqttException e) {
            log.warn("[EMQX-OUT] 断开异常", e);
        }
    }
}
