package com.claw.server.domain.onboarding;

import com.claw.server.common.enums.OnboardingApplicationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 入驻申请单（对应 claw.onboarding_applications，V59）。
 *
 * <p>三类主体（STATION / MANUFACTURER / MERCHANT）<b>共用一张表与同一套状态机</b>（G1），
 * 差异只体现在「材料清单按 applicant_type 配置」这一层，避免三套表三套状态机。
 *
 * <p>状态迁移一律经 {@link OnboardingApplicationStatus#assertTransition}，不走自由 setStatus。
 */
@Entity
@Table(name = "onboarding_applications", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ONB{yyyyMMdd}{4位序号}。 */
    @Column(name = "application_no", nullable = false, unique = true, length = 40)
    private String applicationNo;

    @Column(name = "applicant_type", nullable = false, length = 20)
    private String applicantType;

    @Column(name = "applicant_user_id", nullable = false)
    private Long applicantUserId;

    @Column(nullable = false, length = 24)
    @Builder.Default
    private String status = OnboardingApplicationStatus.DRAFT.name();

    /* ---------------- 主体信息 ---------------- */

    @Column(name = "applicant_name", length = 80)
    private String applicantName;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Column(name = "contact_email", length = 120)
    private String contactEmail;

    @Column(name = "home_address", columnDefinition = "text")
    private String homeAddress;

    /** 身份证编号（AES-GCM 加密存储，列表脱敏展示）。 */
    @Column(name = "id_card_no", length = 255)
    private String idCardNo;

    @Column(name = "kyc_record_id")
    private Long kycRecordId;

    /** PENDING / VERIFIED / REJECTED（冗余，便于筛选）。 */
    @Column(name = "kyc_status", length = 16)
    @Builder.Default
    private String kycStatus = "PENDING";

    @Column(name = "business_scope", columnDefinition = "text")
    private String businessScope;

    /* ---------------- 场地信息 ---------------- */

    @Column(name = "land_intro", columnDefinition = "text")
    private String landIntro;

    @Column(name = "cooperation_plan", columnDefinition = "text")
    private String cooperationPlan;

    /** OWNED 自有 / LEASED 租赁。 */
    @Column(name = "ownership_type", length = 16)
    private String ownershipType;

    @Column(precision = 10, scale = 6)
    private BigDecimal lat;

    @Column(precision = 10, scale = 6)
    private BigDecimal lng;

    @Column(name = "geo_address", columnDefinition = "text")
    private String geoAddress;

    @Column(name = "located_at")
    private Instant locatedAt;

    /* ---------------- 合同与档位 ---------------- */

    /** 签署时锁定的合同版本。 */
    @Column(name = "contract_id")
    private Long contractId;

    /** 冗余版本号快照，防合同表被改后争议（B5）。 */
    @Column(name = "contract_version", nullable = false, length = 20)
    @Builder.Default
    private String contractVersion = "v1.0";

    /** 勾选同意时点（O3：未勾选不可提交）。 */
    @Column(name = "agreed_at")
    private Instant agreedAt;

    @Column(name = "deposit_tier_id")
    private Long depositTierId;

    /* ---------------- 审批与产出 ---------------- */

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reject_reason", columnDefinition = "text")
    private String rejectReason;

    /** 激活后创建的主体 ID（station_id / manufacturer_id / merchant_id）。 */
    @Column(name = "principal_id")
    private Long principalId;

    /** 缴款超时时点（ONBOARDING_DEPOSIT_PAY_DAYS）。 */
    @Column(name = "expire_at")
    private Instant expireAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** 取强类型状态枚举。 */
    public OnboardingApplicationStatus status() {
        return OnboardingApplicationStatus.valueOf(status);
    }

    /**
     * 状态迁移（校验白名单后写值）。
     *
     * @param to 目标状态
     * @throws com.claw.server.common.api.BizException 40940 onboarding.status.illegal
     */
    public void transitTo(OnboardingApplicationStatus to) {
        status().assertTransition(to);
        this.status = to.name();
        this.updatedAt = Instant.now();
    }
}
