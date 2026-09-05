package com.claw.server.domain.contract;

import com.claw.server.common.enums.ContractRefundStatus;
import com.claw.server.common.enums.ContractStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 服务站合约（对应 V73 claw.station_contracts）。
 *
 * <p>生命周期（周老板 2026-09-06 拍板）：每三年一签；到期可选择退出，保证金三月内退还。
 * 签约即随服务站激活生成（{@link com.claw.server.domain.onboarding.OnboardingActivationService}
 * 在回填站点时调用 {@code ContractService.createOnActivation}）。
 *
 * <p>额度口径：credit_limit = 签约时保证金 × 4（见 V72 授信倍率修正），冗余落库便于审计。
 */
@Entity
@Table(name = "station_contracts", schema = "claw",
        uniqueConstraints = @UniqueConstraint(columnNames = {"station_id", "status"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_no", nullable = false, unique = true)
    private String contractNo;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "applicant_type", nullable = false, length = 20)
    @Builder.Default
    private String applicantType = "STATION";

    @Column(name = "deposit_tier_id")
    private Long depositTierId;

    /** 签约时保证金（USD）。 */
    @Column(name = "deposit_amount", nullable = false, precision = 16, scale = 2)
    private BigDecimal depositAmount;

    /** 签约时授信额度（= 保证金 × 4）。 */
    @Column(name = "credit_limit", nullable = false, precision = 16, scale = 2)
    private BigDecimal creditLimit;

    /** 合约年限（默认 3）。 */
    @Column(name = "term_years", nullable = false)
    @Builder.Default
    private Integer termYears = 3;

    @Column(name = "signed_at", nullable = false)
    private Instant signedAt;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to", nullable = false)
    private Instant effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private ContractStatus status = ContractStatus.ACTIVE;

    @Column(name = "exit_requested_at")
    private Instant exitRequestedAt;

    /** 保证金应退截止（EXIT_REQUESTED 后 + 3 月）。 */
    @Column(name = "refund_due_at")
    private Instant refundDueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 24)
    @Builder.Default
    private ContractRefundStatus refundStatus = ContractRefundStatus.NONE;

    /** 实际退款额（可扣残值/违约金后小于 deposit，或 NULL 表示未退）。 */
    @Column(name = "refund_amount", precision = 16, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "remark", columnDefinition = "text")
    private String remark;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private Long updatedBy;
}
