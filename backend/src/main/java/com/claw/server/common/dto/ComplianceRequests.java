package com.claw.server.common.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** 合规域请求（compliance 入参）。 */
public final class ComplianceRequests {

    private ComplianceRequests() {
    }

    /** DTI 强制校验：monthlyDebt / monthlyIncome ≤ 50% 通过。 */
    public record DtiCheck(
            @NotNull Long userId,
            @NotNull @Positive BigDecimal monthlyDebt,     // 月债务（月供 + 预估换电等）
            @NotNull @Positive BigDecimal monthlyIncome,   // 月净收入
            String evidence) {                             // 复核依据 JSON
    }
}
