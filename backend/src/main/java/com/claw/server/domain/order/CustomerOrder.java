package com.claw.server.domain.order;

import com.claw.server.common.enums.OrderStatus;
import com.claw.server.common.enums.UsageMode;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 客户购买订单（对应 V34 claw.customer_orders）。
 * 全款购买 = 买家即时成为资产所有人（残值归买家，D41）；状态机见 CustomerOrderService。
 */
@Entity
@Table(name = "customer_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNo;

    @Column(nullable = false)
    private Long buyerUserId;

    private Long productId;

    private Long assetId;

    @Column(length = 16)
    private String assetType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private UsageMode usageMode = UsageMode.SELF;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private OrderStatus status = OrderStatus.CREATED;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal depositAmount = BigDecimal.ZERO;

    @Column(length = 40)
    private String depositNo;

    @Column(length = 40)
    private String payOrderNo;

    private Long stationId;

    private Long poolEntryId;

    private Long splitRuleId;

    private Long certificateId;

    private Instant shippedAt;

    private Instant completedAt;

    @Column(length = 255)
    private String cancelReason;

    @Column(length = 16)
    private String refundStatus;

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
