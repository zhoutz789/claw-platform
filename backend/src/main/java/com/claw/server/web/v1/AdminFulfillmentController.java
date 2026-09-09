package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.SettlementStatus;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.fulfillment.FulfillmentOrder;
import com.claw.server.domain.fulfillment.FulfillmentOrderItem;
import com.claw.server.domain.fulfillment.FulfillmentService;
import com.claw.server.domain.fulfillment.FulfillmentSettlement;
import com.claw.server.domain.fulfillment.FulfillmentSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** 待履约订单 + 取货扫码履约（增量 B · R6 核心）。取货即触发异步结算（R7）。 */
@RestController
@RequestMapping("/api/v1/admin/fulfillment")
@RequiredArgsConstructor
public class AdminFulfillmentController {

    private final FulfillmentService fulfillmentService;
    private final FulfillmentSettlementService settlementService;

    @GetMapping("/orders")
    @RequirePermission("order:view")
    public ApiResult<List<FulfillmentOrder>> list(@RequestParam(required = false) Long stationId,
                                                 @RequestParam(required = false) Long customerUserId) {
        return ApiResult.ok(fulfillmentService.listOrders(stationId, customerUserId));
    }

    /** 订单详情（含 items 明细，供取货扫码页直接挑选待取设备，无需手填 deviceIds）。 */
    @GetMapping("/orders/{id}")
    @RequirePermission("order:view")
    public ApiResult<FulfillmentOrder> get(@PathVariable Long id) {
        return ApiResult.ok(fulfillmentService.getOrder(id));
    }

    @PostMapping("/orders")
    @RequirePermission("order:fulfill:pay")
    public ApiResult<FulfillmentOrder> create(@RequestBody CreateOrder req) {
        Long op = AuthContext.currentUserId();
        List<FulfillmentOrderItem> items = req.items().stream().map(i -> FulfillmentOrderItem.builder()
                .productId(i.productId())
                .deviceId(i.deviceId())
                .qty(i.qty() == null ? 1 : i.qty())
                .price(i.price())
                .build()).toList();
        return ApiResult.ok(fulfillmentService.createOrder(req.customerUserId(), req.manufacturerId(),
                req.stationId(), req.remoteOrder(), items, req.totalAmount()));
    }

    @PostMapping("/orders/{id}/pay")
    @RequirePermission("order:fulfill:pay")
    public ApiResult<FulfillmentOrder> pay(@PathVariable Long id, @RequestBody(required = false) PayReq req) {
        return ApiResult.ok(fulfillmentService.payOrder(id, req == null ? null : req.paymentRef(), AuthContext.currentUserId()));
    }

    @PostMapping("/orders/{id}/confirm")
    @RequirePermission("order:fulfill:confirm")
    public ApiResult<FulfillmentOrder> confirm(@PathVariable Long id) {
        return ApiResult.ok(fulfillmentService.confirm(id));
    }

    @PostMapping("/orders/{id}/ship")
    @RequirePermission("mfg:fulfill:ship")
    public ApiResult<FulfillmentOrder> ship(@PathVariable Long id) {
        return ApiResult.ok(fulfillmentService.ship(id));
    }

    @PostMapping("/orders/{id}/receive")
    @RequirePermission("station:fulfill:receive")
    public ApiResult<FulfillmentOrder> receive(@PathVariable Long id) {
        return ApiResult.ok(fulfillmentService.receive(id));
    }

    /** 取货扫码履约：扣减服务站寄售占有权 + 建用户设备授权 + 写 outbox 触发异步结算（Q6 解耦）。 */
    @PostMapping("/orders/{id}/pickup")
    @RequirePermission("order:pickup:scan")
    public ApiResult<FulfillmentOrder> pickup(@PathVariable Long id, @RequestBody PickupReq req) {
        Long op = AuthContext.currentUserId();
        return ApiResult.ok(fulfillmentService.pickupScan(id, req.deviceIds(), op));
    }

    @PostMapping("/orders/{id}/cancel")
    public ApiResult<FulfillmentOrder> cancel(@PathVariable Long id) {
        return ApiResult.ok(fulfillmentService.cancel(id));
    }

    @PostMapping("/orders/{id}/expire")
    public ApiResult<FulfillmentOrder> expire(@PathVariable Long id) {
        return ApiResult.ok(fulfillmentService.expire(id));
    }

    /* ===================== 履约结算人工介入（R7 兜底：失败挂起转人工） ===================== */

    /**
     * 结算单看板：列出待人工处理的结算单。
     * 默认 MANUAL + FAILED；可 {@code ?status=MANUAL,FAILED} 指定多个，{@code ?orderId=} 过滤单笔订单。
     */
    @GetMapping("/settlements")
    @RequirePermission("order:view")
    public ApiResult<List<FulfillmentSettlement>> listSettlements(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long orderId) {
        return ApiResult.ok(settlementService.listSettlements(parseStatuses(status), orderId));
    }

    /** 结算单详情（含失败原因、处理人、重试次数）。 */
    @GetMapping("/settlements/{id}")
    @RequirePermission("order:view")
    public ApiResult<FulfillmentSettlement> getSettlement(@PathVariable Long id) {
        return ApiResult.ok(settlementService.getSettlement(id));
    }

    /**
     * 人工重结算：针对 MANUAL/FAILED 挂起单，修复根因（绑定收款户 / 配置提成规则 / 校正金额）后，
     * 重新跑完整资金链路（释放冻结 + 提成 + 货款），结果回填既有行。
     */
    @PostMapping("/settlements/{id}/retry")
    @RequirePermission("order:fulfill:pay")
    public ApiResult<FulfillmentSettlement> retry(@PathVariable Long id,
                                                  @RequestBody(required = false) ManualAction req) {
        return ApiResult.ok(settlementService.retry(id, AuthContext.currentUserId(),
                req == null ? null : req.remark()));
    }

    /**
     * 人工置已处理（行政关闭）：释放用户冻结资金回可用余额（行政退款），结算单置 DONE。
     * 不给付服务站 / 厂家，适用于「根因无法修复、订单终止 / 退款给用户」的场景。
     */
    @PostMapping("/settlements/{id}/resolve")
    @RequirePermission("order:fulfill:pay")
    public ApiResult<FulfillmentSettlement> resolve(@PathVariable Long id,
                                                    @RequestBody(required = false) ManualAction req) {
        return ApiResult.ok(settlementService.resolve(id, AuthContext.currentUserId(),
                req == null ? null : req.remark()));
    }

    /** 解析逗号分隔的 settlement 状态过滤参数；非法值 → 400。 */
    private List<SettlementStatus> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        List<SettlementStatus> out = new java.util.ArrayList<>();
        for (String s : status.split(",")) {
            String t = s.trim().toUpperCase();
            if (!t.isEmpty()) {
                try {
                    out.add(SettlementStatus.valueOf(t));
                } catch (IllegalArgumentException e) {
                    throw BizException.of(BizException.INVALID_PARAM, "settlement.status.invalid", t);
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    public record ManualAction(String remark) {
    }

    public record CreateOrder(Long customerUserId, Long manufacturerId, Long stationId, boolean remoteOrder,
                             BigDecimal totalAmount, List<FulfillmentItemReq> items) {
    }

    public record FulfillmentItemReq(Long productId, Long deviceId, Integer qty, BigDecimal price) {
    }

    public record PayReq(String paymentRef) {
    }

    public record PickupReq(List<Long> deviceIds) {
    }
}
