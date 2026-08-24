package com.claw.server.domain.sharedpool;

import com.claw.server.common.enums.RentalOrderStatus;
import com.claw.server.common.enums.RentalType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 租赁订单（对应 V12 claw.rental_orders）。
 * 统一抽象换电租赁和车辆租赁。
 */
@Entity
@Table(name = "rental_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RentalOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNo;

    @Column(nullable = false)
    private Long assetId;

    private Long poolEntryId;

    @Column(nullable = false)
    private Long renterUserId;

    private Long stationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RentalType rentalType;

    private Long swapOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RentalOrderStatus status = RentalOrderStatus.CREATED;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal ownerShare = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal stationShare = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal platformShare = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal insuranceShare = BigDecimal.ZERO;

    private Instant startedAt;
    private Instant completedAt;

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
