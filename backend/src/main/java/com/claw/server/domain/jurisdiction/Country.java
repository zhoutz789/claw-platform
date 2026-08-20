package com.claw.server.domain.jurisdiction;

import com.claw.server.common.enums.CountryRegion;
import com.claw.server.common.enums.JurisdictionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 法域/国家主表（countries）。 */
@Entity
@Table(name = "countries", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
public class Country {

    @Id
    @Column(name = "code", length = 3)
    private String code;

    @Column(name = "code_iso2", length = 2, nullable = false)
    private String codeIso2;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_local")
    private String nameLocal;

    @Enumerated(EnumType.STRING)
    @Column(name = "region", nullable = false, length = 24)
    private CountryRegion region;

    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    @Column(name = "default_locale", length = 8, nullable = false)
    private String defaultLocale;

    @Column(name = "pilot_order", nullable = false)
    private int pilotOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private JurisdictionStatus status;

    @Column(name = "data_residency", nullable = false)
    private boolean dataResidency;

    @Column(name = "regulatory_note", columnDefinition = "text")
    private String regulatoryNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
