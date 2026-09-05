package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 合规域视图（compliance 出参）。 */
public final class ComplianceViews {

    private ComplianceViews() {
    }

    public static record DtiCheckView(
            Long id, Long userId,
            BigDecimal monthlyDebt, BigDecimal monthlyIncome,
            BigDecimal dtiRate, String result, Instant checkedAt) {
    }

    public static record TextHitView(
            String pattern, String severity, String category, String excerpt) {
    }

    public static record TextCheckView(
            boolean allowed, String result, List<TextHitView> hits) {
    }
}
