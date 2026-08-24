package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 电池扩展（对应 claw.batteries）。
 * v2.0：deposit_value = 固定30%押金（取消动态残值概念，D36 定稿）。
 * SOH 仍持续追踪用于残值评估（D41 残值回收），但不再驱动押金金额。
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
    private BigDecimal soh = BigDecimal.valueOf(100.00);   // 健康度 %（用于残值评估，不再驱动押金）

    @Builder.Default
    private Integer cycleCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal depositValue = BigDecimal.ZERO;     // 押金（固定30%，平台设定，D36）

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
