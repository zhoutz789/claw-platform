package com.claw.server.web.v1;

import com.claw.server.common.api.BizException;
import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.PaymentRequests;
import com.claw.server.common.dto.PaymentViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.payment.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
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

    @Value("${claw.security.dev-open-access:false}")
    private boolean devOpenAccess;

    /**
     * 支付网关回调共享密钥（webhook 鉴权，fail-closed）。
     * 生产必须配置环境变量 CLAW_PAYMENT_CALLBACK_SECRET；网关在 X-Callback-Token 头携带该值。
     * 未配置时回调接口拒绝服务，杜绝"任意已登录用户可触发入账"的资金注入漏洞。
     */
    @Value("${claw.payment.callback-secret:}")
    private String callbackSecret;

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
    public ApiResult<PaymentViews.WalletTxnView> callback(@PathVariable String orderNo, HttpServletRequest request) {
        verifyCallbackAuth(request);
        return ApiResult.ok(paymentService.onCallback(orderNo));
    }

    @PostMapping("/payments/txns/{txnNo}/fail")
    public ApiResult<PaymentViews.WalletTxnView> fail(@PathVariable String txnNo,
                                                      @RequestParam(required = false) String reason,
                                                      HttpServletRequest request) {
        verifyCallbackAuth(request);
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

    /**
     * 取当前登录用户 ID；无认证上下文时返回 401（不是 500）。
     *
     * <p>此前抛 IllegalStateException("unauthenticated")，而全局异常处理器没有对应
     * handler —— 会兜成 500 + "internal error"。未登录是客户端问题，正确语义是 401：
     * 客户端据此跳登录页，而不是展示「服务异常」。
     *
     * <p><b>为什么 E2E 抓不到</b>：dev-open-access=true 会注入虚拟操作员 id=1，
     * uid == null 这条路径走不到，所以开着 dev 开关冒烟永远是绿的。验证必须关掉该开关，
     * 见 scripts/e2e-smoke.sh 的未认证探测轮。
     */
    /**
     * 支付网关回调鉴权（fail-closed）。
     *
     * <p>回调是 ABA/Bakong 网关主动推送的 webhook，不应以用户 JWT 鉴权；
     * 正确做法是校验网关签名/共享密钥。当前以共享密钥 {@code X-Callback-Token} 头做 interim 防护。
     * 生产必须配置 {@code claw.payment.callback-secret}（环境变量 CLAW_PAYMENT_CALLBACK_SECRET），
     * 未配置则拒绝（fail-closed），杜绝"任意已登录用户可触发入账"的资金注入漏洞。
     * dev-open-access 模式下整体放开（本地联调方便）。
     */
    private void verifyCallbackAuth(HttpServletRequest request) {
        if (devOpenAccess) {
            return;
        }
        if (callbackSecret == null || callbackSecret.isBlank()) {
            throw BizException.unauthorized("error.payment.callback.unauthorized");
        }
        String token = request.getHeader("X-Callback-Token");
        if (token == null || !constantTimeEquals(token, callbackSecret)) {
            throw BizException.unauthorized("error.payment.callback.unauthorized");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < ab.length; i++) {
            result |= ab[i] ^ bb[i];
        }
        return result == 0;
    }

    private Long requireOperator() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw BizException.unauthorized("error.auth.unauthenticated");
        }
        return uid;
    }
}
