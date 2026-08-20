package com.claw.server.common.dto;

import java.time.Instant;

/** 信用域视图（credit 出参）。 */
public final class CreditViews {

    private CreditViews() {
    }

    /** Claw Score 信用分。 */
    public record CreditScoreView(
            Long userId, Integer score, String factors, Instant updatedAt) {
    }

    /** 信用分变更事件。 */
    public record CreditEventView(
            Long userId, Integer delta, String reason, String refId, Instant createdAt) {
    }
}
