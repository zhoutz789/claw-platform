package com.claw.server.common.dto;

import com.claw.server.common.enums.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** 支付域请求（payment 入参）。 */
public final class PaymentRequests {

    private PaymentRequests() {
    }

    /** 充值（KHQR / Bakong 收单）。 */
    public record Recharge(
            @Positive BigDecimal amount,
            String method) {
    }

    /** 三专户定向收单（KHQR 收单入账到指定专户）。 */
    public record Collect(
            @Positive BigDecimal amount,
            AccountType escrowType) {
    }

    /** 提现（ABA 出金）。 */
    public record Withdraw(
            @Positive BigDecimal amount,
            @NotBlank String bankAccount,
            String bankName) {
    }
}
