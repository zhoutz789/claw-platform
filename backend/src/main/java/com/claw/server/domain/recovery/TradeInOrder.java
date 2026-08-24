package com.claw.server.domain.recovery;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 以旧换新订单（对应 V13 claw.trade_in_orders）。
 * 旧资产回收 + 新资产购买 = 一笔合并交易。
 */
@Entity
@Table(name = "trade_in_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeInOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNo;

    @Column(nullable = false)
    private Long oldAssetId;

    @Column(nullable = false)
    private BigDecimal oldValuation;

    @Column(nullable = false)
    private Long newAssetId;

    @Column(nullable = false)
    private BigDecimal newPrice;

    @Column(nullable = false)
    private BigDecimal priceDifference;

    private Long recoveryOrderId;

    @Column(nullable = false)
    private Long ownerUserId;

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

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
