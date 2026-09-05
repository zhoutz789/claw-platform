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

    /** 文案合规扫描：扫描营销/承诺类文本是否含禁止表述（合规文案护栏）。 */
    public record TextCheck(
            String text,       // 待扫描文案
            String scene) {    // 业务场景（CAPACITY_PLAN/PRODUCT_LISTING/STATION_AD…），仅留痕
    }
}
