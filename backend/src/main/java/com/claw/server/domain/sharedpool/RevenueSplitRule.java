package com.claw.server.domain.sharedpool;

import com.claw.server.common.enums.RevenueShareBasis;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 分成规则（对应 V12 claw.revenue_split_rules）。
 * 所有人≥50% / 站点≥15% / 平台=10% / 保险=5%。
 */
@Entity
@Table(name = "revenue_split_rules", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevenueSplitRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    private Long poolEntryId;

    @Column(nullable = false)
    private BigDecimal ownerRate;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal stationRate = BigDecimal.valueOf(0.15);

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal platformRate = BigDecimal.valueOf(0.10);

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal insuranceRate = BigDecimal.valueOf(0.05);

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RevenueShareBasis shareBasis = RevenueShareBasis.PER_SWAP;

    @Column(nullable = true)
    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

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
