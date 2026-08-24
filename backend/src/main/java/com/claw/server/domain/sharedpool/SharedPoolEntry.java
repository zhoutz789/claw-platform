package com.claw.server.domain.sharedpool;

import com.claw.server.common.enums.PoolEntryStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 共享池入池记录（对应 V12 claw.shared_pool_entries）。
 * 资产所有人将资产放入共享池，指定投放站点和分成比例。
 */
@Entity
@Table(name = "shared_pool_entries", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SharedPoolEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private Long ownerUserId;

    @Column(nullable = false)
    private Long ownershipId;

    private Long currentStationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PoolEntryStatus status = PoolEntryStatus.IN_POOL;

    @Column(nullable = false)
    private BigDecimal ownerSplitRate;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal stationSplitRate = BigDecimal.valueOf(0.15);

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal dailyUsageFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal perSwapFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private Integer selfChargeFreeWindowMinutes = 120;

    @Column(nullable = false)
    @Builder.Default
    private Instant pooledAt = Instant.now();

    private Instant removedAt;

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
