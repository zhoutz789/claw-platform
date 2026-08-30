package com.claw.server.domain.fulfillment;

import com.claw.server.common.enums.SettlementStatus;
import com.claw.server.common.enums.SettlementStep;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算台账（对应 V50 claw.fulfillment_settlements，R7）。
 * 按序：物流费（厂家承担）→ 服务站提成 → 余额归厂家；单步失败挂起（Q6）。
 */
@Entity
@Table(name = "fulfillment_settlements", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FulfillmentSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String settlementNo;

    @Column(nullable = false)
    private Long fulfillmentOrderId;

    private Long manufacturerId;

    private Long stationId;

    @Builder.Default
    private BigDecimal logisticsFee = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal balanceToMfg = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementStatus status;

    private String ledgerTxnId;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
