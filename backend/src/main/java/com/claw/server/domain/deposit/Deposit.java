package com.claw.server.domain.deposit;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 押金单（对应 claw.deposits）。
 * 流转链路：客户支付押金 → HELD 冻结 → 归还 RETURNED / 违约扣收 FORFEITED。
 * 扣收金额转入残值准备金专户（RESIDUAL_RESERVE，D27 escrow 口径）。
 */
@Entity
@Table(name = "deposits", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Deposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String depositNo;

    @Column(nullable = false)
    private Long userId;

    private Long assetId;

    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "HELD";   // HELD | RETURNED | FORFEITED

    private String payOrderNo;

    private String forfeitReason;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
