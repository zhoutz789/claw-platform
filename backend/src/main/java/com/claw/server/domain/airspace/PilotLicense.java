package com.claw.server.domain.airspace;

import jakarta.persistence.*;
import com.claw.server.common.enums.PilotLicenseType;
import lombok.*;

import java.time.LocalDate;
import java.time.Instant;

/**
 * 飞手资质（对应 claw.pilot_licenses）。
 * SSCA（柬埔寨民航局）签发，按作业类型分级；飞行计划须绑定有效资质。
 */
@Entity
@Table(name = "pilot_licenses", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PilotLicense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String licenseNo;

    @Column(nullable = false)
    private String holderName;

    /** AGRICULTURE 植保 / LOGISTICS 物流 / INSPECTION 巡检 / RESCUE 救援。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PilotLicenseType ltype;

    @Column(nullable = false)
    private String issuer;   // 签发机构，默认 SSCA

    @Column(nullable = false)
    private LocalDate expiryDate;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
