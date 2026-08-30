package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.inventory.Inventory;
import com.claw.server.domain.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 库存台账 + 寄售占有权视图（增量 B · R3/B4）。 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
@RequiredArgsConstructor
public class AdminInventoryController {

    private final InventoryService inventoryService;

    @GetMapping
    public ApiResult<List<Inventory>> list(@RequestParam(required = false) Long manufacturerId,
                                          @RequestParam(required = false) Long stationId,
                                          @RequestParam(required = false) String ownershipType) {
        if (stationId != null) {
            return ApiResult.ok(inventoryService.listByStation(stationId));
        }
        if (manufacturerId != null) {
            if (ownershipType != null) {
                return ApiResult.ok(inventoryService.listByManufacturer(manufacturerId).stream()
                        .filter(i -> i.getOwnershipType() == OwnershipType.valueOf(ownershipType)).toList());
            }
            return ApiResult.ok(inventoryService.listByManufacturer(manufacturerId));
        }
        return ApiResult.ok(inventoryService.listByOwnership(
                ownershipType == null ? OwnershipType.CONSIGNED : OwnershipType.valueOf(ownershipType)));
    }

    @GetMapping("/device/{deviceId}")
    public ApiResult<Inventory> getByDevice(@PathVariable Long deviceId) {
        return ApiResult.ok(inventoryService.getByDevice(deviceId));
    }

    /** 发货至服务站：建立寄售占有权（Q2 占有权转移点）。 */
    @PostMapping("/ship")
    @RequirePermission("mfg:transfer:create")
    public ApiResult<Void> shipToStation(@RequestBody ShipReq req) {
        Long op = AuthContext.currentUserId();
        inventoryService.shipToStation(req.deviceId(), req.stationId(), req.manufacturerId(), op);
        return ApiResult.ok();
    }

    public record ShipReq(Long deviceId, Long stationId, Long manufacturerId) {
    }
}
