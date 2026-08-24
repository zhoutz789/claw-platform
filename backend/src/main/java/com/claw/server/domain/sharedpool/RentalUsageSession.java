package com.claw.server.domain.sharedpool;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 使用明细（对应 V12 claw.rental_usage_sessions）。
 * 按量计费拆分：基础费 + 使用费 + 占用费。
 */
@Entity
@Table(name = "rental_usage_sessions", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RentalUsageSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long rentalOrderId;

    private BigDecimal usageKwh;
    private BigDecimal usageHours;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal baseFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal usageFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal occupancyFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal totalFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSelfCharge = false;

    private Integer selfChargeMinutes;

    @Column(nullable = false)
    private Instant sessionStart;

    private Instant sessionEnd;

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
