package com.claw.server.domain.settlement;

import com.claw.server.common.enums.SettleStep;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算台账（对应 V50 claw.settlements，R7/B6）。
 * 设备销售履约结算专用，与光伏 revenue_split_rules / revenue_settlements 完全解耦。
 * 按 SettleStep 顺序推进：LOGISTICS → COMMISSION → BALANCE_TO_MFG（单步失败挂起，Q6）。
 * status ∈ {PENDING, SETTLED, MANUAL, FAILED}；manual=TRUE 表示单步失败转人工处理。
 */
@Entity
@Table(name = "settlements", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_no", nullable = false, unique = true)
    private String settlementNo;

    /** 业务引用类型：FULFILLMENT / TRANSFER / RECOVERY。 */
    @Column(name = "biz_ref_type", nullable = false, length = 20)
    private String bizRefType;

    @Column(name = "biz_ref_id", nullable = false)
    private Long bizRefId;

    @Enumerated(EnumType.STRING)
    @Column(name = "settle_step", nullable = false, length = 20)
    private SettleStep settleStep;

    @Column(name = "payer_account_id")
    private Long payerAccountId;

    @Column(name = "payee_account_id")
    private Long payeeAccountId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    /** PENDING / SETTLED / MANUAL / FAILED。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "PENDING";

    /** 单步失败挂起（Q6）：TRUE=转人工处理，不再自动推进。 */
    @Column(nullable = false)
    @Builder.Default
    private boolean manual = false;

    @Column(columnDefinition = "text")
    private String remark;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
