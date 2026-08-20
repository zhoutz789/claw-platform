package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.PaymentRequests;
import com.claw.server.common.dto.PaymentViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.payment.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 支付域接口（S4）：ABA 托管资金路径。
 *
 * <p>POST /accounts/recharge           充值（KHQR 收单）
 * POST /accounts/withdraw            提现（ABA 出金）
 * POST /payments/khr-collect          三专户定向收单（残值准备金/电池基金/车辆风险金）
 * POST /payments/{orderNo}/callback   ABA 回调（对账后入账，防重复）
 * POST /payments/txns/{txnNo}/fail    模拟提现出金失败退回（运维/测试）
 * GET  /payments/txns                 我的充值/提现流水
 * GET  /payments/txns/{txnNo}         交易单详情
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/accounts/recharge")
    public ApiResult<PaymentViews.RechargeView> recharge(@Valid @RequestBody PaymentRequests.Recharge req) {
        return ApiResult.ok(paymentService.recharge(requireOperator(), req));
    }

    @PostMapping("/accounts/withdraw")
    public ApiResult<PaymentViews.WalletTxnView> withdraw(@Valid @RequestBody PaymentRequests.Withdraw req) {
        return ApiResult.ok(paymentService.withdraw(requireOperator(), req));
    }

    @PostMapping("/payments/khr-collect")
    public ApiResult<PaymentViews.CollectView> collect(@Valid @RequestBody PaymentRequests.Collect req) {
        return ApiResult.ok(paymentService.collect(requireOperator(), req));
    }

    @PostMapping("/payments/{orderNo}/callback")
    public ApiResult<PaymentViews.WalletTxnView> callback(@PathVariable String orderNo) {
        return ApiResult.ok(paymentService.onCallback(orderNo));
    }

    @PostMapping("/payments/txns/{txnNo}/fail")
    public ApiResult<PaymentViews.WalletTxnView> fail(@PathVariable String txnNo,
                                                      @RequestParam(required = false) String reason) {
        return ApiResult.ok(paymentService.failWithdraw(txnNo, reason != null ? reason : "ABA payout failed"));
    }

    @GetMapping("/payments/txns")
    public ApiResult<List<PaymentViews.WalletTxnView>> listTxns() {
        return ApiResult.ok(paymentService.listTxns(requireOperator()));
    }

    @GetMapping("/payments/txns/{txnNo}")
    public ApiResult<PaymentViews.WalletTxnView> getTxn(@PathVariable String txnNo) {
        return ApiResult.ok(paymentService.getTxn(txnNo));
    }

    private Long requireOperator() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw new IllegalStateException("unauthenticated");
        }
        return uid;
    }
}
