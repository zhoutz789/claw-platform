package com.claw.server.domain.onboarding;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 入驻保证金档位（对应 claw.onboarding_deposit_tiers，V60）。
 *
 * <p>Q1 拍板 3 档：5,000 / 20,000 / 50,000；Q2b 授信额度 = 保证金 × 倍率（默认 3），
 * 额度含义 =「该主体可持有的寄售设备名义货值上限」（非现金授信、非贷款）。
 *
 * <p><b>倍率可配，禁止硬编码</b>：档位级 {@code credit_multiplier}，
 * 全局默认取 {@code system_config.ONBOARDING_CREDIT_MULTIPLIER_DEFAULT}，
 * 绝对覆盖值 {@code credit_limit_override}（非 NULL 时绕过倍率）。
 * 额度计算的唯一真源见 {@code CreditLimitService.resolveTierCreditLimit(tier)}。
 */
@Entity
@Table(name = "onboarding_deposit_tiers", schema = "claw",
        uniqueConstraints = @UniqueConstraint(columnNames = {"applicant_type", "tier_code"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingDepositTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "applicant_type", nullable = false, length = 20)
    private String applicantType;

    /** BASIC / STANDARD / PREMIUM。 */
    @Column(name = "tier_code", nullable = false, length = 40)
    private String tierCode;

    @Column(name = "tier_name", nullable = false, length = 80)
    private String tierName;

    /** 保证金金额。 */
    @Column(name = "deposit_amount", nullable = false, precision = 16, scale = 2)
    private BigDecimal depositAmount;

    /** 授信倍率（可配，非硬编码）。 */
    @Column(name = "credit_multiplier", nullable = false, precision = 8, scale = 4)
    @Builder.Default
    private BigDecimal creditMultiplier = new BigDecimal("3.0000");

    /** 绝对额度覆盖值；为 NULL 时按 deposit_amount × credit_multiplier 计算。 */
    @Column(name = "credit_limit_override", precision = 16, scale = 2)
    private BigDecimal creditLimitOverride;

    /** CONSIGNMENT_VALUE（寄售货值上限）。 */
    @Column(name = "credit_type", nullable = false, length = 24)
    @Builder.Default
    private String creditType = "CONSIGNMENT_VALUE";

    @Column(nullable = false, length = 8)
    @Builder.Default
    private String currency = "USD";

    /** 权益说明（对外展示，用于引导升档）。 */
    @Column(name = "benefit_desc", columnDefinition = "text")
    private String benefitDesc;

    @Column(name = "sort_no", nullable = false)
    @Builder.Default
    private Integer sortNo = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private Long updatedBy;
}
