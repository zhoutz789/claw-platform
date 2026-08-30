package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.recovery.DeviceRecoveryOrder;
import com.claw.server.domain.recovery.RecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 设备回收单管理（增量 B · R8）。超时未成交自动回收（扫描器在 RecoveryService 内）。 */
@RestController
@RequestMapping("/api/v1/admin/recovery")
@RequiredArgsConstructor
public class AdminRecoveryController {

    private final RecoveryService recoveryService;

    @GetMapping("/orders")
    public ApiResult<List<DeviceRecoveryOrder>> list(@RequestParam(required = false) Long manufacturerId) {
        return ApiResult.ok(recoveryService.listRecoveries(manufacturerId));
    }

    @GetMapping("/orders/{id}")
    public ApiResult<DeviceRecoveryOrder> get(@PathVariable Long id) {
        return ApiResult.ok(recoveryService.getRecovery(id));
    }

    @PostMapping("/orders")
    @RequirePermission("mfg:recovery:create")
    public ApiResult<DeviceRecoveryOrder> create(@RequestBody CreateRecovery req) {
        Long op = AuthContext.currentUserId();
        return ApiResult.ok(recoveryService.createRecovery(req.manufacturerId(), req.stationId(),
                req.assetId(), req.reason(), req.triggerType(), op));
    }

    @PostMapping("/orders/{id}/confirm")
    @RequirePermission("station:recovery:confirm")
    public ApiResult<DeviceRecoveryOrder> confirm(@PathVariable Long id) {
        Long op = AuthContext.currentUserId();
        return ApiResult.ok(recoveryService.confirmRecovery(id, op));
    }

    public record CreateRecovery(Long manufacturerId, Long stationId, Long assetId,
                                String reason, String triggerType) {
    }
}
