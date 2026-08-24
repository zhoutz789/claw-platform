package com.claw.server.domain.recovery;

import com.claw.server.common.enums.ValuationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 残值评估（对应 V13 claw.residual_valuations）。
 * 三方估价：系统 + 站方 + 第三方，最终价取中位数（R7 防操纵）。
 */
@Entity
@Table(name = "residual_valuations", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResidualValuation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private Long ownerUserId;

    private BigDecimal soh;
    private BigDecimal usageYears;
    private String brand;
    private String model;
    private Integer cycleCount;

    private BigDecimal systemEstimate;
    private BigDecimal stationEstimate;
    private BigDecimal thirdPartyEstimate;

    private String thirdPartyName;
    private String thirdPartyReportUrl;

    private BigDecimal finalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ValuationStatus status = ValuationStatus.PENDING;

    private Long evaluatedBy;
    private Instant evaluatedAt;

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
