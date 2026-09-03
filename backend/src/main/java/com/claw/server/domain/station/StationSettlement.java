package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 服务站寄售结算单（模块四 · 结算层）。
 *
 * <p>三金额字段由结算层独立计算（BC-1/BC-2）：物流费（厂家承担）、服务站提成、厂家净额。
 * 与既有 {@code CrossBorderSettlementService} 并列，不复用其逻辑。
 */
@Entity
@Table(name = "station_settlements", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String settlementNo;

    @Column(nullable = false)
    private Long stationId;

    private Instant periodStart;

    private Instant periodEnd;

    /** DRAFT / CONFIRMED / PAID。 */
    @Column(nullable = false)
    @Builder.Default
    private String status = "DRAFT";

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal logisticsFee = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal stationCommission = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal manufacturerNet = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private String currency = "USD";

    private Long createdBy;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant confirmedAt;

    private Instant paidAt;
}
