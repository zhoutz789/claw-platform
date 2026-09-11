package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.BmsTelemetryReport;
import com.claw.server.domain.iot.BmsAdapter;
import com.claw.server.domain.iot.BmsTelemetryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 锂电池 BMS 遥测接入（锂电池 BMS 对接方案 Phase A）。
 *
 * <p>真实链路：保护板 → 边缘网关 {@code BmsAdapter} 归一化 → EMQX {@code claw/iot/{deviceNo}/telemetry}
 * → {@code EmqxMqttInboundAdapter} → {@code BmsTelemetryService}。
 *
 * <p>本端点用于无真实 MQTT 时的 Mock 灌入 / 边缘网关联调：直接提交规范 JSON，走与 MQTT 完全相同的
 * 归一化 + 落库路径，使「BMS 上报 → 入库 → 后台可见」可独立验证。
 */
@RestController
@RequestMapping("/api/v1/bms")
@RequiredArgsConstructor
public class BmsTelemetryController {

    private final BmsAdapter bmsAdapter;
    private final BmsTelemetryService bmsTelemetryService;
    private final ObjectMapper objectMapper;

    @PostMapping("/telemetry")
    public ApiResult<Void> push(@RequestBody JsonNode body) {
        BmsTelemetryReport report = bmsAdapter.normalize(body, BmsAdapter.Profile.GENERIC_MQTT);
        bmsTelemetryService.handleReport(report, null);
        return ApiResult.ok(null);
    }
}
