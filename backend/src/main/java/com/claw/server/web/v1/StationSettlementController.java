package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.station.StationScopeService;
import com.claw.server.domain.station.StationSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 服务站结算层端点（模块四 · ③，前缀 /api/v1/station/settlements）。
 *
 * <p>读接口按 {@link StationScopeService#allowedStationIds} 过滤（BC-5）。
 * 生成草稿：平台管理员（manage）或服务站（view，D7「可发起草稿」）均可；
 * 确认 / 支付：仅平台管理员（manage）。
 */
@RestController
@RequestMapping("/api/v1/station/settlements")
@RequiredArgsConstructor
public class StationSettlementController {

    private final StationSettlementService settlementService;
    private final StationScopeService scopeService;

    @GetMapping
    @RequirePermission({"station:settlement:view", "mfg:station:settlement:view"})
    public ApiResult<List<StationViews.StationSettlementView>> list(
            @RequestParam(required = false) Long stationId) {
        return ApiResult.ok(settlementService.list(scopeService.allowedStationIds(stationId)));
    }

    @GetMapping("/{id}")
    @RequirePermission({"station:settlement:view", "mfg:station:settlement:view"})
    public ApiResult<StationViews.StationSettlementDetailView> get(@PathVariable Long id) {
        return ApiResult.ok(settlementService.get(id));
    }

    @PostMapping("/generate")
    @RequirePermission({"station:settlement:manage", "station:settlement:view"})
    public ApiResult<StationViews.StationSettlementView> generate(@RequestBody StationRequests.StationSettlementGenerate req) {
        return ApiResult.ok(settlementService.generate(req, AuthContext.currentUserId()));
    }

    @PostMapping("/{id}/confirm")
    @RequirePermission("station:settlement:manage")
    public ApiResult<StationViews.StationSettlementView> confirm(@PathVariable Long id) {
        return ApiResult.ok(settlementService.confirm(id, AuthContext.currentUserId()));
    }

    @PostMapping("/{id}/pay")
    @RequirePermission("station:settlement:manage")
    public ApiResult<StationViews.StationSettlementView> pay(@PathVariable Long id) {
        return ApiResult.ok(settlementService.pay(id, AuthContext.currentUserId()));
    }
}
