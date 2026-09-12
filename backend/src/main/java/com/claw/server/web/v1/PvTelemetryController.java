package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.RequirePermission;
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
 * <p><b>权限收口（V116）：</b>{@code POST /telemetry} 已加 {@code @RequirePermission("pv:telemetry:push")}，
 * 权限位与菜单由 V116 迁移统一播种，角色授权走三步法（MANUFACTURER/REGULATOR 可见菜单，PLATFORM_ADMIN 通配放行）。
 * 遥测写入属设备/数采器接入动作，仅持 {@code pv:telemetry:push} 的角色可调用。
 */
@RestController
@RequestMapping("/api/v1/pv")
@RequiredArgsConstructor
public class PvTelemetryController {

    private final PvAdapter pvAdapter;
    private final PvTelemetryService pvTelemetryService;
    private final ObjectMapper objectMapper;

    @RequirePermission("pv:telemetry:push")
    @PostMapping("/telemetry")
    public ApiResult<Void> push(@RequestBody JsonNode body) {
        com.claw.server.common.dto.PvTelemetryReport report =
                pvAdapter.normalize(body, PvAdapter.Profile.GENERIC_MQTT);
        pvTelemetryService.handleReport(report, null);
        return ApiResult.ok(null);
    }
}
