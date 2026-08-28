package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.enums.DroneSafetyCause;
import com.claw.server.common.enums.DroneSafetyStatus;
import com.claw.server.domain.airspace.DroneSafetyEvent;
import com.claw.server.domain.airspace.DroneSafetyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 无人机飞行安全管控接口（类比车辆断缴锁车）。
 * 安全态由 OPEN 安全事件推导：存在即 LOCKED，禁止起飞。
 */
@RestController
@RequestMapping("/api/v1/drone-safety")
@RequiredArgsConstructor
public class DroneSafetyController {

    private final DroneSafetyService safetyService;

    @GetMapping("/{assetId}")
    public ApiResult<DroneSafetyStatus> status(@PathVariable Long assetId) {
        return ApiResult.ok(safetyService.currentStatus(assetId));
    }

    @GetMapping("/{assetId}/events")
    public ApiResult<List<DroneSafetyEvent>> events(@PathVariable Long assetId) {
        return ApiResult.ok(safetyService.listEvents(assetId));
    }

    @PostMapping("/{assetId}/simulate")
    public ApiResult<DroneSafetyEvent> simulate(@PathVariable Long assetId,
                                                @RequestBody SimulateReq req) {
        return ApiResult.ok(safetyService.simulate(assetId, req.cause(), req.detail()));
    }

    @PostMapping("/{assetId}/resolve")
    public ApiResult<DroneSafetyEvent> resolve(@PathVariable Long assetId) {
        return ApiResult.ok(safetyService.resolve(assetId));
    }

    public record SimulateReq(DroneSafetyCause cause, String detail) {
    }
}
