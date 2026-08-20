package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 电池扩展（对应 claw.batteries）。
 * deposit_value = 动态残值（FIFO 派发前提），由 deposit_curves（S2 V3）计算后回填。
 */
@Entity
@Table(name = "batteries", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Battery {

    @Id
    @Column(name = "asset_id")
    private Long assetId;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private BigDecimal capacityKwh;

    private String protocolVer;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal soh = BigDecimal.valueOf(100.00);   // 健康度 %

    @Builder.Default
    private Integer cycleCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal depositValue = BigDecimal.ZERO;     // 押金=残值

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
