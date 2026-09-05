package com.claw.server.domain.capacity;

import com.claw.server.common.enums.RebateStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 回佣明细（对应 V71 claw.capacity_rebate_settlements）。
 * 每笔租赁结算的厂家 owner_share，按 unit_count/total_units 拆分到各定购单位用户。
 */
@Entity
@Table(name = "capacity_rebate_settlements", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapacityRebateSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String settlementNo;

    private Long rentalOrderId;

    @Column(nullable = false)
    private Long planId;

    private Long poolEntryId;

    @Column(nullable = false)
    private BigDecimal ownerShareBase;

    @Column(nullable = false)
    private BigDecimal rebateTotal;

    @Column(nullable = false)
    private Long subscriberUserId;

    @Column(nullable = false)
    private Integer unitCount;

    @Column(nullable = false)
    private BigDecimal ratio;

    @Column(nullable = false)
    private BigDecimal amount;

    private String ledgerTxnId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RebateStatus status = RebateStatus.SETTLED;

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
