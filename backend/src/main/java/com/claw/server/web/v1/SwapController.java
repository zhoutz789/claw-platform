package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.SwapRequests;
import com.claw.server.common.dto.SwapViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.swap.SwapBillingService;
import com.claw.server.domain.swap.SwapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 换电域（S3）：换电单状态机 + 计价预览。
 *
 * <p>POST /swap-orders             创建换电单（押金冻结 + 预扣）
 * POST /swap-orders/{no}/confirm   服务站确认双向流转（管理权切换）
 * POST /swap-orders/{no}/settle    结算（多退少补）
 * POST /swap-orders/{no}/cancel    取消（解冻退回）
 * GET  /swap-orders/{no}/events    订单事件流
 * GET  /swap-orders/quote          计价预览（锁版价）
 */
@RestController
@RequestMapping("/api/v1/swap-orders")
@RequiredArgsConstructor
public class SwapController {

    private final SwapService swapService;
    private final SwapBillingService billingService;

    @PostMapping
    public ApiResult<SwapViews.SwapOrderView> create(@Valid @RequestBody SwapRequests.Create req) {
        return ApiResult.ok(swapService.create(req, requireOperator()));
    }

    @GetMapping
    public ApiResult<List<SwapViews.SwapOrderView>> listMine() {
        return ApiResult.ok(swapService.listByUser(requireOperator()));
    }

    @GetMapping("/{no}")
    public ApiResult<SwapViews.SwapOrderView> get(@PathVariable String no) {
        return ApiResult.ok(swapService.get(no));
    }

    @PostMapping("/{no}/confirm")
    public ApiResult<SwapViews.SwapOrderView> confirm(@PathVariable String no) {
        return ApiResult.ok(swapService.confirm(no, requireOperator()));
    }

    @PostMapping("/{no}/settle")
    public ApiResult<SwapViews.SwapOrderView> settle(@PathVariable String no,
                                                     @Valid @RequestBody(required = false) SwapRequests.Settle req) {
        return ApiResult.ok(swapService.settle(no, req != null ? req : new SwapRequests.Settle(null, null),
                requireOperator()));
    }

    @PostMapping("/{no}/cancel")
    public ApiResult<SwapViews.SwapOrderView> cancel(@PathVariable String no,
                                                     @Valid @RequestBody(required = false) SwapRequests.Cancel req) {
        return ApiResult.ok(swapService.cancel(no, req != null ? req.reason() : null, requireOperator()));
    }

    @GetMapping("/{no}/events")
    public ApiResult<List<SwapViews.EventView>> events(@PathVariable String no) {
        return ApiResult.ok(swapService.events(no));
    }

    /** 计价预览：GET /swap-orders/quote?kwh=2（锁版：电费 0.12 + 服务费 0.32）。 */
    @GetMapping("/quote")
    public ApiResult<SwapViews.QuoteView> quote(@RequestParam(required = false) BigDecimal kwh) {
        return ApiResult.ok(billingService.quote(kwh != null ? kwh : new BigDecimal("2.00")));
    }

    private Long requireOperator() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw new IllegalStateException("unauthenticated");
        }
        return uid;
    }
}
