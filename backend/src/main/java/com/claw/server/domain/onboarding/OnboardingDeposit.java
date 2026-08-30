package com.claw.server.domain.onboarding;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 入驻保证金缴纳台账（对应 claw.onboarding_deposits，V60）。
 *
 * <p>⚠️ 资金域纪律（设计 §1.5）：本表是<b>业务台账，不是复式记账</b>，
 * 本轮<b>不写任何 ledger / account_entries 分录</b>。若后续要求保证金入账，
 * 必须跨域调用 {@code AccountService} / {@code LedgerService}，不得直连其 Repository（ArchUnit 会拦）。
 *
 * <p>首期线下转账 + 财务人工确认（Q4）；P1 接在线支付时仅增 {@code ONLINE} 枚举值，表结构不变。
 */
@Entity
@Table(name = "onboarding_deposits", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingDeposit {

    /** 缴款状态。 */
    public enum Status {
        PENDING_CONFIRM, CONFIRMED, REJECTED, REFUNDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** DEP{yyyyMMdd}{4位序号}。 */
    @Column(name = "deposit_no", nullable = false, unique = true, length = 40)
    private String depositNo;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    /** 冗余主体类型，激活后按组织查。 */
    @Column(name = "principal_type", nullable = false, length = 20)
    private String principalType;

    /** 激活后回填的主体 ID。 */
    @Column(name = "principal_id")
    private Long principalId;

    @Column(name = "tier_id")
    private Long tierId;

    /** 实缴金额。 */
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    @Builder.Default
    private String currency = "USD";

    /** OFFLINE_TRANSFER（首期）/ ONLINE（P1）。 */
    @Column(name = "pay_method", nullable = false, length = 24)
    @Builder.Default
    private String payMethod = "OFFLINE_TRANSFER";

    /** 转账凭证。 */
    @Column(name = "voucher_url", length = 500)
    private String voucherUrl;

    @Column(name = "payer_name", length = 120)
    private String payerName;

    @Column(name = "payer_account", length = 120)
    private String payerAccount;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = Status.PENDING_CONFIRM.name();

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** 是否已确认到账。 */
    public boolean isConfirmed() {
        return Status.CONFIRMED.name().equals(status);
    }
}
