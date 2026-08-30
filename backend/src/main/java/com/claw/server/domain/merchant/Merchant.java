package com.claw.server.domain.merchant;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 商家主体（对应 V52 claw.merchants，商家入驻骨架）。
 * 完整招商审批流留 Phase 2；本轮回填骨架（状态 PENDING/ACTIVE/REJECTED 由审批流推进）。
 */
@Entity
@Table(name = "merchants", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 160)
    private String contact;

    @Column(length = 64)
    private String country;

    /** PENDING / ACTIVE / REJECTED。 */
    @Column(nullable = false, length = 24)
    @Builder.Default
    private String status = "PENDING";

    /* ---------------- 入驻治理字段（增量 C · V60） ---------------- */

    @Column(name = "onboarding_status", length = 24)
    @Builder.Default
    private String onboardingStatus = "PENDING";

    @Column(name = "onboarding_application_id")
    private Long onboardingApplicationId;

    @Column(name = "deposit_tier_id")
    private Long depositTierId;

    @Column(name = "credit_limit", precision = 16, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "disabled_by")
    private Long disabledBy;

    @Column(name = "disabled_reason", columnDefinition = "text")
    private String disabledReason;

    /**
     * 可选的「经营所在地服务站」（增量 C §1.2 裁定）。
     *
     * <p>取代 V52 的 {@code stations.merchant_id}：商家是<b>独立主体</b>（Q9），
     * 外键方向必须是 N merchants → 1 station，放在 stations 侧等于在库层写死「一站仅容一商」，
     * 会堵死 Phase 2 的铺位招商。
     *
     * <p>语义：仅用于「附近商家」检索与未来铺位招商落位，
     * <b>不表示任何归属或挂靠关系</b>；商家账号、保证金、授信额度全部独立。允许为 NULL。
     */
    @Column(name = "affiliate_station_id")
    private Long affiliateStationId;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Boolean deleted = false;
}
