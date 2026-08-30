package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.transfer.TransferOrder;
import com.claw.server.domain.transfer.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** 站间调拨单管理（增量 B · R5/B7）。扫码交接推进寄售占有权（Q2）。 */
@RestController
@RequestMapping("/api/v1/admin/transfers")
@RequiredArgsConstructor
public class AdminTransferController {

    private final TransferService transferService;

    @GetMapping
    public ApiResult<List<TransferOrder>> list(@RequestParam(required = false) Long manufacturerId) {
        return ApiResult.ok(transferService.listTransfers(manufacturerId));
    }

    @GetMapping("/{id}")
    public ApiResult<TransferOrder> get(@PathVariable Long id) {
        return ApiResult.ok(transferService.getTransfer(id));
    }

    @PostMapping
    @RequirePermission("mfg:transfer:create")
    public ApiResult<TransferOrder> create(@RequestBody CreateTransfer req) {
        Long op = AuthContext.currentUserId();
        return ApiResult.ok(transferService.createTransfer(req.manufacturerId(), req.fromStationId(),
                req.toStationId(), req.deviceIds(), req.logisticsFee(), op));
    }

    /** 源站扫码交接：占有权转出，在途（Q2）。 */
    @PostMapping("/{id}/handover")
    @RequirePermission("station:transfer:handover")
    public ApiResult<TransferOrder> handover(@PathVariable Long id) {
        Long op = AuthContext.currentUserId();
        return ApiResult.ok(transferService.handover(id, op));
    }

    /** 目标站扫码收货：建新占有权，库存到站（Q2）。 */
    @PostMapping("/{id}/receive")
    @RequirePermission("station:transfer:receive")
    public ApiResult<TransferOrder> receive(@PathVariable Long id) {
        Long op = AuthContext.currentUserId();
        return ApiResult.ok(transferService.receive(id, op));
    }

    public record CreateTransfer(Long manufacturerId, Long fromStationId, Long toStationId,
                                List<Long> deviceIds, BigDecimal logisticsFee) {
    }
}
