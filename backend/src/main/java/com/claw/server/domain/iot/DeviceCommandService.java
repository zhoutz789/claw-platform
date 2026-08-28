package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTRequests;
import com.claw.server.common.dto.IoTViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 设备指令服务（出站半边业务层）：构造带签名 + 防重放 + 安全条件的下行报文，
 * 落库待回执（PENDING），经 EMQX 下发；并处理上行 cmd_ack 关联更新。
 *
 * <p>EMQX 未启用（{@code claw.iot.emqx.enabled=false}，默认）时，指令仅落库不下发，
 * 便于无 Broker 环境联调；启用后自动经网关发布。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceCommandService {

    private final DeviceRepository deviceRepository;
    private final DeviceCommandRepository commandRepository;
    private final ObjectProvider<EmqxMqttCommandGateway> mqttGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 下发指令：签名 → 落库 → 发布。 */
    @Transactional
    public IoTViews.CommandView issue(String deviceNo, IoTRequests.IssueCommand req) {
        Device device = deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> BizException.notFound("error.iot.device.not.found"));

        String cmdId = "cmd-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 4);
        String nonce = MqttSigner.nonce();
        long ts = Instant.now().getEpochSecond();

        // 下行报文：先构造「不含 sign」的固定顺序载荷并按契约签名，再附上 sign。
        // 固定顺序 cmdId/action/params/ts/nonce，便于设备端剔除 sign 后按同一顺序复算比对。
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("cmdId", cmdId);
        base.put("action", req.action());
        base.put("params", req.params());
        base.put("ts", ts);
        base.put("nonce", nonce);
        String payloadJson;
        String sign;
        try {
            payloadJson = objectMapper.writeValueAsString(base);
            sign = MqttSigner.hmacSha256(payloadJson, device.getSecret() != null ? device.getSecret() : "");
            base.put("sign", sign);
            payloadJson = objectMapper.writeValueAsString(base);
        } catch (Exception e) {
            throw new IllegalStateException("下行报文构造失败", e);
        }

        DeviceCommand cmd = DeviceCommand.builder()
                .deviceId(device.getId())
                .deviceNo(deviceNo)
                .action(req.action())
                .paramsJson(toJson(req.params()))
                .cmdId(cmdId)
                .nonce(nonce)
                .sign(sign)
                .status("PENDING")
                .tenantId(device.getTenantId())
                .createdAt(Instant.now())
                .build();
        commandRepository.save(cmd);

        EmqxMqttCommandGateway gw = mqttGateway.getIfAvailable();
        if (gw != null) {
            gw.publish(deviceNo, payloadJson);
        } else {
            log.warn("[CMD] EMQX 未启用，指令已落库但未下发 deviceNo={} cmdId={}", deviceNo, cmdId);
        }

        return toView(cmd);
    }

    /** 上行 cmd_ack 到达：关联并更新指令状态。 */
    @Transactional
    public void handleAck(IoTRequests.CommandAck ack) {
        commandRepository.findByCmdId(ack.cmdId()).ifPresent(cmd -> {
            String result = ack.result() != null ? ack.result().toUpperCase() : "FAIL";
            cmd.setStatus(result);
            cmd.setResult(result);
            cmd.setDetail(ack.detail());
            cmd.setAckedAt(Instant.now());
            commandRepository.save(cmd);
            log.info("指令回执 cmdId={} result={} detail={}", ack.cmdId(), result, ack.detail());
        });
    }

    @Transactional(readOnly = true)
    public List<IoTViews.CommandView> history(String deviceNo) {
        return commandRepository.findByDeviceNoOrderByCreatedAtDesc(deviceNo).stream()
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public IoTViews.CommandView pending(String cmdId) {
        return commandRepository.findByCmdId(cmdId).map(this::toView).orElse(null);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private IoTViews.CommandView toView(DeviceCommand c) {
        return new IoTViews.CommandView(c.getId(), c.getDeviceNo(), c.getAction(),
                c.getCmdId(), c.getStatus(), c.getResult(), c.getDetail(), c.getCreatedAt(), c.getAckedAt());
    }

    /** 下行报文结构（选型书 4.3）：cmdId / action / params / ts / nonce / sign。 */
    public static class Downlink {
        public String cmdId;
        public String action;
        public Object params;
        public long ts;
        public String nonce;
        public String sign;

        public Downlink(String cmdId, String action, Object params, long ts, String nonce, String sign) {
            this.cmdId = cmdId;
            this.action = action;
            this.params = params;
            this.ts = ts;
            this.nonce = nonce;
            this.sign = sign;
        }
    }
}
