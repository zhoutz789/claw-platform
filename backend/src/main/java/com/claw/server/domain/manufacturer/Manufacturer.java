package com.claw.server.domain.manufacturer;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** 厂家（专业组织公司），由平台管理员维护。对应 claw.manufacturers。 */
@Entity
@Table(name = "manufacturers", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Manufacturer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String contact;
    private String country;

    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

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

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Builder.Default
    private Boolean deleted = false;
}
