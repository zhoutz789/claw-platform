package com.claw.server.domain.jurisdiction;

import com.claw.server.common.enums.PartnerType;
import com.claw.server.common.enums.ShareBasis;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 合作伙伴分润规则（partner_programs）：共营分润引擎雏形。 */
@Entity
@Table(name = "partner_programs", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
public class PartnerProgram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_code", length = 3, nullable = false)
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "partner_type", length = 24, nullable = false)
    private PartnerType partnerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "share_basis", length = 24, nullable = false)
    private ShareBasis shareBasis;

    @Column(name = "share_pct", nullable = false)
    private java.math.BigDecimal sharePct = java.math.BigDecimal.ZERO;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
