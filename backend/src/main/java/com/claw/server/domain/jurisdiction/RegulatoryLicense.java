package com.claw.server.domain.jurisdiction;

import com.claw.server.common.enums.LicenseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 监管牌照注册表（regulatory_licenses）：每国必牌照/已获/申请中。 */
@Entity
@Table(name = "regulatory_licenses", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
public class RegulatoryLicense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_code", length = 3, nullable = false)
    private String countryCode;

    @Column(name = "license_type", length = 32, nullable = false)
    private String licenseType;

    @Column(name = "authority")
    private String authority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private LicenseStatus status;

    @Column(name = "required", nullable = false)
    private boolean required = true;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
