package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 支付域视图（payment 出参）。 */
public final class PaymentViews {

    private PaymentViews() {
    }

    /** 充值收单结果（含 KHQR 码）。 */
    public record RechargeView(
            String orderNo,
            String khqr,
            BigDecimal amountUsd,
            String paymentMethod,
            String status) {
    }

    /** 三专户收单结果。 */
    public record CollectView(
            String orderNo,
            String khqr,
            BigDecimal amountUsd,
            String escrowType,
            String status) {
    }

    /** 钱包交易单（充值/提现）。 */
    public record WalletTxnView(
            String txnNo,
            Long userId,
            String txnType,
            BigDecimal amountUsd,
            BigDecimal feeUsd,
            String channel,
            String status,
            String abaRef,
            String failReason,
            Instant createdAt) {
    }

    /** 日终对账批次。 */
    public record ReconciliationView(
            LocalDate runDate,
            String status,
            BigDecimal platformTotal,
            BigDecimal bankTotal,
            int matchedCount,
            int mismatchCount,
            String detailJson,
            Instant createdAt) {
    }
}
