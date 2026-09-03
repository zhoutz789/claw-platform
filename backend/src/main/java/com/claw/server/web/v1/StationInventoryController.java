package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.station.StationInventoryService;
import com.claw.server.domain.station.StationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 服务站库存层端点（模块四 · ①，前缀 /api/v1/station/inventory）。
 *
 * <p>读接口按 {@link StationScopeService#allowedStationIds} 过滤（BC-5）；
 * 写接口先校验目标站是否在允许集合内，越权抛 40301。
 */
@RestController
@RequestMapping("/api/v1/station/inventory")
@RequiredArgsConstructor
public class StationInventoryController {

    private final StationInventoryService inventoryService;
    private final StationScopeService scopeService;

    @GetMapping
    @RequirePermission({"station:inventory:view", "mfg:inventory:consignment:view"})
    public ApiResult<List<StationViews.StationInventoryStockView>> list(
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) String skuCode) {
        return ApiResult.ok(inventoryService.listStock(scopeService.allowedStationIds(stationId), skuCode));
    }

    @GetMapping("/movements")
    @RequirePermission({"station:inventory:view", "mfg:inventory:consignment:view"})
    public ApiResult<List<StationViews.StationInventoryMovementView>> movements(
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) String skuCode) {
        return ApiResult.ok(inventoryService.listMovements(scopeService.allowedStationIds(stationId), skuCode));
    }

    @GetMapping("/me")
    @RequirePermission({"station:inventory:view", "mfg:inventory:consignment:view"})
    public ApiResult<StationViews.StationScopeView> me(
            @RequestParam(required = false) Long stationId) {
        return ApiResult.ok(scopeService.resolveView(stationId));
    }

    @PostMapping("/inbound")
    @RequirePermission("station:inventory:inbound")
    public ApiResult<StationViews.StationInventoryStockView> inbound(@RequestBody StationRequests.StationInbound req) {
        assertStationAllowed(req.stationId());
        return ApiResult.ok(inventoryService.inbound(req.stationId(), req.skuCode(), req.qty()));
    }

    @PostMapping("/adjust")
    @RequirePermission("station:inventory:adjust")
    public ApiResult<StationViews.StationInventoryStockView> adjust(@RequestBody StationRequests.StationAdjust req) {
        assertStationAllowed(req.stationId());
        return ApiResult.ok(inventoryService.adjust(req.stationId(), req.skuCode(), req.deltaQty(), req.reason()));
    }

    /** 写操作越权校验：目标站必须在当前账号允许集合内（BC-5）。 */
    private void assertStationAllowed(Long stationId) {
        List<Long> allowed = scopeService.allowedStationIds(stationId);
        if (allowed != null && !allowed.contains(stationId)) {
            throw BizException.of(40301, "station.scope.forbidden");
        }
    }
}
