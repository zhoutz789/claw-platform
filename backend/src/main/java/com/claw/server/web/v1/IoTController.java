package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.IoTRequests;
import com.claw.server.common.dto.IoTViews;
import com.claw.server.domain.iot.IoTService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * IoT 域接口（S5）：遥测/轨迹上报与查询（REST 模拟 MQTT 上报）。
 *
 * <p>POST /iot/telemetry                   遥测上报（更新最新遥测 + 落轨迹点）
 * GET  /assets/{assetId}/telemetry         资产最新遥测
 * GET  /assets/{assetId}/tracks            资产轨迹（?from=&to=）
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class IoTController {

    private final IoTService iotService;

    @PostMapping("/iot/telemetry")
    public ApiResult<IoTViews.TelemetryView> reportTelemetry(@Valid @RequestBody IoTRequests.TelemetryReport req) {
        return ApiResult.ok(iotService.reportTelemetry(req));
    }

    @GetMapping("/assets/{assetId}/telemetry")
    public ApiResult<IoTViews.TelemetryView> latestTelemetry(@PathVariable Long assetId) {
        return ApiResult.ok(iotService.latest(assetId));
    }

    @GetMapping("/assets/{assetId}/tracks")
    public ApiResult<List<IoTViews.TrackView>> tracks(@PathVariable Long assetId,
                                                      @RequestParam(required = false) Instant from,
                                                      @RequestParam(required = false) Instant to) {
        return ApiResult.ok(iotService.tracks(assetId, from, to));
    }
}
