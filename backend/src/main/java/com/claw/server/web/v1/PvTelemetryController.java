package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.domain.iot.PvAdapter;
import com.claw.server.domain.iot.PvTelemetryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 光伏遥测接入（光伏数据链路切片）。
 *
 * <p>真实链路：逆变器/电表/气象站 → 数采器 {@code PvAdapter} 归一化 →
 * EMQX {@code claw/iot/{deviceNo}/telemetry} → {@code EmqxMqttInboundAdapter} →
 * {@code PvTelemetryService}。
 *
 * <p>本端点用于无真实 MQTT 时的 Mock 灌入 / 数采器联调：直接提交规范 JSON，
 * 走与 MQTT 完全相同的归一化 + 落库路径，使「光伏上报 → 入库 → 小时电量」可独立验证。
 *
 * <p><b>权限说明：</b>本切片刻意<b>不加</b>权限注解（{@code @RequirePermission} 等），
 * 与 {@code BmsTelemetryController} 保持一致的接入节奏；鉴权与租户隔离由后续「光伏权限收口」
 * 切片统一补齐（届时与 MQTT 入站链路一并加），此处不留半套注解以免产生"已鉴权"的假象。
 */
@RestController
@RequestMapping("/api/v1/pv")
@RequiredArgsConstructor
public class PvTelemetryController {

    private final PvAdapter pvAdapter;
    private final PvTelemetryService pvTelemetryService;
    private final ObjectMapper objectMapper;

    @PostMapping("/telemetry")
    public ApiResult<Void> push(@RequestBody JsonNode body) {
        com.claw.server.common.dto.PvTelemetryReport report =
                pvAdapter.normalize(body, PvAdapter.Profile.GENERIC_MQTT);
        pvTelemetryService.handleReport(report, null);
        return ApiResult.ok(null);
    }
}
