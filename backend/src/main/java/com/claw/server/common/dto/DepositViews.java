package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** 押金域视图（deposit 出参）。 */
public final class DepositViews {

    private DepositViews() {
    }

    public static record DepositView(
            Long id, String depositNo, Long userId, Long assetId,
            BigDecimal amount, String status, String payOrderNo,
            String forfeitReason, Instant createdAt, Instant updatedAt) {
    }
}
