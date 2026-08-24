package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** 资产使用记录（里程/循环/能耗）。对应 claw.asset_usage_records。 */
@Entity
@Table(name = "asset_usage_records", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    private Instant periodStart;
    private Instant periodEnd;

    @Builder.Default
    private BigDecimal mileageKm = BigDecimal.ZERO;
    @Builder.Default
    private Integer cycles = 0;
    @Builder.Default
    private BigDecimal energyKwh = BigDecimal.ZERO;
    private String note;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
