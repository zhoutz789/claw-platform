package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.AdminDtos.*;
import com.claw.server.common.enums.RentalOrderStatus;
import com.claw.server.common.enums.SwapStatus;
import com.claw.server.domain.sharedpool.RentalOrder;
import com.claw.server.domain.sharedpool.RentalOrderRepository;
import com.claw.server.domain.swap.SwapOrder;
import com.claw.server.domain.swap.SwapOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import com.claw.server.common.security.RequirePermission;

/**
 * 后台订单模块（S5 补齐）：换电订单管理 + 租赁订单只读。
 *
 * <p>GET    /orders/swap              换电订单列表
 * GET    /orders/swap/{id}           换电订单详情
 * PUT    /orders/swap/{id}/status    调整订单状态（运营操作）
 * GET    /orders/rental              租赁订单列表（只读）
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final SwapOrderRepository swapOrderRepository;
    private final RentalOrderRepository rentalOrderRepository;

    @GetMapping("/swap")
    public ApiResult<List<SwapOrderView>> listSwapOrders() {
        return ApiResult.ok(swapOrderRepository.findAll().stream()
                .filter(o -> !Boolean.TRUE.equals(o.getDeleted()))
                .map(this::toSwapView).toList());
    }

    @GetMapping("/swap/{id}")
    public ApiResult<SwapOrderView> swapDetail(@PathVariable Long id) {
        SwapOrder o = swapOrderRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "order.swap.not.found"));
        return ApiResult.ok(toSwapView(o));
    }

    @PutMapping("/swap/{id}/status")
    @RequirePermission("order:update")
    public ApiResult<SwapOrderView> updateSwapStatus(@PathVariable Long id,
                                                     @RequestBody SwapOrderStatusReq req) {
        SwapOrder o = swapOrderRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "order.swap.not.found"));
        if (req.status() != null) o.setStatus(SwapStatus.valueOf(req.status()));
        if (req.cancelReason() != null) o.setCancelReason(req.cancelReason());
        o.setUpdatedAt(Instant.now());
        return ApiResult.ok(toSwapView(swapOrderRepository.save(o)));
    }

    @GetMapping("/rental")
    public ApiResult<List<RentalOrderView>> listRentalOrders() {
        return ApiResult.ok(rentalOrderRepository.findAll().stream()
                .filter(o -> !Boolean.TRUE.equals(o.getDeleted()))
                .map(this::toRentalView).toList());
    }

    private SwapOrderView toSwapView(SwapOrder o) {
        return new SwapOrderView(o.getId(), o.getOrderNo(), o.getUserId(), o.getStationId(),
                o.getVehicleId(), o.getBatteryOutId(), o.getBatteryInId(),
                o.getStatus() == null ? null : o.getStatus().name(), o.getBatteryDeposit(),
                o.getOldBatteryDeposit(), o.getEstKwh(), o.getEstElecFee(), o.getEstServiceFee(),
                o.getEstTotal(), o.getActualTotal(), o.getSocStart(), o.getSocEnd(), o.getSettleStatus(),
                o.getCancelReason(), o.getCreatedAt());
    }

    private RentalOrderView toRentalView(RentalOrder o) {
        return new RentalOrderView(o.getId(), o.getOrderNo(), o.getAssetId(), o.getRenterUserId(),
                o.getStationId(), o.getRentalType() == null ? null : o.getRentalType().name(),
                o.getStatus() == null ? null : o.getStatus().name(), o.getTotalFee(), o.getOwnerShare(),
                o.getStationShare(), o.getPlatformShare(), o.getInsuranceShare(), o.getStartedAt(),
                o.getCompletedAt());
    }
}
