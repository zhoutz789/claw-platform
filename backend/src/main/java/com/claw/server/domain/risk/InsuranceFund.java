package com.claw.server.domain.risk;

import com.claw.server.common.enums.FundStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "insurance_fund", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsuranceFund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalBalance = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalCollected = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalClaimed = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalRecovered = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal coverageRatio = BigDecimal.valueOf(0.10);

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalAssetValue = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal coverageActual = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FundStatus status = FundStatus.HEALTHY;

    @Column(nullable = false)
    @Builder.Default
    private Instant lastUpdatedAt = Instant.now();

    private Long lastUpdatedBy;
    private String auditNotes;

    @Builder.Default
    private Long tenantId = 1L;

    @Builder.Default
    private Boolean deleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
