package com.claw.server.domain.manufacturer;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** 采购订单（客户购买）。对应 claw.purchase_orders。 */
@Entity
@Table(name = "purchase_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNo;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long skuId;

    private Long buyerId;

    @Column(nullable = false)
    @Builder.Default
    private Integer qty = 1;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private String currency = "USD";

    /** CREATED / PAID / SHIPPED / CANCELLED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "CREATED";

    private Instant paidAt;
    private Instant shippedAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Builder.Default
    private Boolean deleted = false;
}
