package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.InventoryViews;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.inventory.Inventory;
import com.claw.server.domain.inventory.InventoryScope;
import com.claw.server.domain.inventory.InventoryScopeService;
import com.claw.server.domain.inventory.InventoryViewAssembler;
import com.claw.server.domain.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 库存台账 + 寄售占有权视图（增量 B · R3/B4 + 模块三）。 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
@RequiredArgsConstructor
public class AdminInventoryController {

    private final InventoryService inventoryService;
    /** 模块三：作用域解析（主体 → 作用域 → 越权校验 → 下属站点反查）。 */
    private final InventoryScopeService scopeService;

    // 读接口同样鉴权：厂家看自有/寄售、服务站看寄售，三者命中其一即放行（平台管理员靠 "*" 通配）。
    @GetMapping
    @RequirePermission({"mfg:inventory:own", "mfg:inventory:consignment:view", "station:consignment:view"})
    public ApiResult<List<InventoryViews.InventoryRowView>> list(
            @RequestParam(required = false) Long manufacturerId,
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) String ownershipType) {
        List<Inventory> rows;
        if (stationId != null) {
            rows = inventoryService.listByStation(stationId);
        } else if (manufacturerId != null) {
            rows = inventoryService.listByManufacturer(manufacturerId);
            if (ownershipType != null) {
                OwnershipType ot = parseOwnership(ownershipType);
                rows = rows.stream().filter(i -> i.getOwnershipType() == ot).toList();
            }
        } else {
            OwnershipType ot = ownershipType == null ? OwnershipType.CONSIGNED : parseOwnership(ownershipType);
            rows = inventoryService.listByOwnership(ot);
        }
        // 模块三 · H-7：收口为安全 DTO，不暴露 unitValue/valueCurrency/unitValueSource
        return ApiResult.ok(rows.stream().map(InventoryViewAssembler::toRowView).toList());
    }

    @GetMapping("/device/{deviceId}")
    @RequirePermission({"mfg:inventory:own", "mfg:inventory:consignment:view", "station:consignment:view"})
    public ApiResult<InventoryViews.InventoryRowView> getByDevice(@PathVariable Long deviceId) {
        // 模块三 · H-7：收口为安全 DTO
        return ApiResult.ok(InventoryViewAssembler.toRowView(inventoryService.getByDevice(deviceId)));
    }

    /** 角色作用域库存双视图（模块三 · M3-1/2/3）。 */
    @GetMapping("/me")
    @RequirePermission({"mfg:inventory:own", "mfg:inventory:consignment:view", "station:consignment:view"})
    public ApiResult<InventoryViews.InventoryScopeView> me(
            @RequestParam(required = false) Long manufacturerId,
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ownershipType,
            @RequestParam(required = false, defaultValue = "false") boolean includeStats,
            @RequestParam(required = false, defaultValue = "true") boolean withRows,
            @RequestParam(required = false, defaultValue = "500") int limit) {
        InventoryScope.ScopeInfo scope = scopeService.resolveCurrent(manufacturerId, stationId);
        OwnershipType ot = ownershipType == null ? null : parseOwnership(ownershipType);
        LifecycleStatus st = status == null ? null : parseStatus(status);
        InventoryViews.InventoryFilter filter = new InventoryViews.InventoryFilter(
                st == null ? null : st.name(),
                ot == null ? null : ot.name(),
                stationId, withRows, limit);
        InventoryViews.InventoryScopeView view = inventoryService.listScoped(scope, filter);
        if (includeStats) {
            view = view.withStats(inventoryService.statsOf(scope));
        }
        return ApiResult.ok(view);
    }

    /** 统计报表（模块三 · M3-4）。 */
    @GetMapping("/stats")
    @RequirePermission({"mfg:inventory:own", "mfg:inventory:consignment:view", "station:consignment:view"})
    public ApiResult<InventoryViews.InventoryStatsView> stats(
            @RequestParam(required = false) Long manufacturerId,
            @RequestParam(required = false) Long stationId) {
        InventoryScope.ScopeInfo scope = scopeService.resolveCurrent(manufacturerId, stationId);
        return ApiResult.ok(inventoryService.statsOf(scope));
    }

    // ------------------------------------------------------------------
    // V82 下线说明（寄售入库发起方改造）：
    // 原「POST /ship 厂家选站发货到服务站」端点（权限 mfg:transfer:create，
    // 入参 ShipReq(deviceId, stationId, manufacturerId)）已删除。
    // 寄售入库自 V82 起改由服务站自主发起：
    //   POST /api/v1/station/consignment/inbound
    //   （StationConsignmentController，权限 station:consignment:inbound；
    //    站点 ID 由登录站长作用域带出、厂家 ID 由 inventory.owner_manufacturer_id 带出，
    //    均不来自入参；写入链路完整复用 InventoryService.shipToStationBatch → shipOne）。
    // 厂家不再分拨到站 —— 「谁操作数据是谁的，谁的数据是谁的」；
    // 厂家侧只保留本控制器的只读库存视图（list / device/{id} / me / stats）。
    // ------------------------------------------------------------------

    /** 枚举参数校验（非法值 → 400，而非 500）。 */
    private static OwnershipType parseOwnership(String s) {
        try {
            return OwnershipType.valueOf(s.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw BizException.invalidParam("inventory.param.invalid", s);
        }
    }

    private static LifecycleStatus parseStatus(String s) {
        try {
            return LifecycleStatus.valueOf(s.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw BizException.invalidParam("inventory.param.invalid", s);
        }
    }
}
