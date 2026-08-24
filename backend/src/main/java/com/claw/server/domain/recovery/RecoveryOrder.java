package com.claw.server.domain.recovery;

import com.claw.server.common.enums.RecoveryOrderStatus;
import com.claw.server.common.enums.RecoveryType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 回收订单（对应 V13 claw.recovery_orders）。
 * 现金回收 / 以旧换新两种模式。
 */
@Entity
@Table(name = "recovery_orders", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecoveryOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNo;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private Long ownerUserId;

    private Long ownershipId;

    @Column(nullable = false)
    private Long valuationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecoveryType recoveryType;

    @Column(nullable = false)
    private BigDecimal recoveryPrice;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal processingFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal netAmount = BigDecimal.ZERO;

    private Long newAssetId;
    private BigDecimal newAssetPrice;
    private BigDecimal priceDifference;

    @Column(nullable = false)
    @Builder.Default
    private String fundSource = "PLATFORM_FUND";

    private String ledgerTxnId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RecoveryOrderStatus status = RecoveryOrderStatus.CREATED;

    private Instant confirmedAt;
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
