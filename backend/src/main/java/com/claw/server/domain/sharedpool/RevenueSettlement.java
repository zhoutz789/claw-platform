package com.claw.server.domain.sharedpool;

import com.claw.server.common.enums.SettlementStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 分账结算记录（对应 V12 claw.revenue_settlements）。
 * 按日/周/月结算：总收益拆分到各方账户。
 */
@Entity
@Table(name = "revenue_settlements", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevenueSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String settlementNo;

    @Column(nullable = false)
    private LocalDate settlementDate;

    private Long stationId;
    private Long poolEntryId;

    @Column(nullable = false)
    private BigDecimal totalRevenue;

    @Column(nullable = false)
    private BigDecimal ownerShare;

    @Column(nullable = false)
    private BigDecimal stationShare;

    @Column(nullable = false)
    private BigDecimal platformShare;

    @Column(nullable = false)
    private BigDecimal insuranceShare;

    private String ledgerTxnId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SettlementStatus status = SettlementStatus.PENDING;

    @Column(nullable = false)
    private Instant periodStart;

    @Column(nullable = false)
    private Instant periodEnd;

    private Instant settledAt;

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
