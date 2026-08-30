package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 站点（对应 claw.stations）：换电 / 现货交付网点。
 * 客户选购 = 附近站点现货（周老板验收口径）；country_code 关联法域。
 */
@Entity
@Table(name = "stations", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Station {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String area;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String countryCode = "KHM";

    private String province;
    private String city;
    private String district;

    private BigDecimal lat;
    private BigDecimal lng;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String openHours = "24H";

    private Long operatorId;

    @Builder.Default
    private Long tenantId = 1L;

    /* ---------------- 入驻治理字段（增量 C · V60） ---------------- */

    /**
     * 入驻状态：PENDING / ACTIVATED / DISABLED / REJECTED（见 {@link OnboardingStatus}）。
     * 与 {@link #status}（运营状态 ACTIVE/CLOSED/BUILDING）<b>正交并存</b>：
     * 前者是平台治理的准入状态，后者是运营状态，二者可同时为 DISABLED + CLOSED。
     * 激活态是 {@code ACTIVATED} 而非 {@code ACTIVE} —— {@code ACTIVE} 属于运营状态列
     * {@link #status}，两者同名会写错 SQL。
     * C 端检索条件为 {@code status='ACTIVE' AND onboarding_status='ACTIVATED'}。
     */
    @Column(name = "onboarding_status", length = 24)
    @Builder.Default
    private String onboardingStatus = "PENDING";

    /** 来源入驻申请单。 */
    @Column(name = "onboarding_application_id")
    private Long onboardingApplicationId;

    @Column(name = "deposit_tier_id")
    private Long depositTierId;

    /** 授信额度（寄售设备名义货值上限），冗余自档位，校验时零 join。NULL = 不校验（历史站点）。 */
    @Column(name = "credit_limit", precision = 16, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "disabled_by")
    private Long disabledBy;

    /** 禁用原因（平台禁用时必填留痕）。 */
    @Column(name = "disabled_reason", columnDefinition = "text")
    private String disabledReason;

    /** 门头照片（对外展示图标，O14）：激活时由申请单 SIGNBOARD 已审核通过的首图回填。 */
    @Column(name = "signboard_url", length = 500)
    private String signboardUrl;

    /** 定位反查地址。 */
    @Column(name = "geo_address", columnDefinition = "text")
    private String geoAddress;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
