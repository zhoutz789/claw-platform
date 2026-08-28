package com.claw.server.domain.deposit;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 阶梯押金规则（对应 V34 claw.deposit_rules，R4）。
 * 按资产类型 + 第 N 年给出押金率（车辆/电池同比例：0.30/0.25/0.20/0.15）。
 */
@Entity
@Table(name = "deposit_rules", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepositRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String assetType;

    @Column(nullable = false)
    private Integer yearIndex;

    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal depositRate;

    @Column(nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal minRate = new BigDecimal("0.15");

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
