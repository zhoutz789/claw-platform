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

    /** 订单总金额（结算恒等式右端；V87 新增）。 */
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 本次结算实际采用的物流费率（如 0；违法率按 0 处理并告警，V87 新增）。 */
    @Builder.Default
    private BigDecimal logisticsFeeRate = BigDecimal.ZERO;

    /** 命中提成规则主键（快照溯源，V87 新增）。 */
    private Long commissionRuleId;

    /** 命中提成规则快照 JSON（审计留痕，V87 新增）。 */
    @Column(columnDefinition = "TEXT")
    private String commissionRuleSnapshot;

    /** 下单用户 id（释放其冻结，V87 新增）。 */
    private Long customerUserId;

    /** 结算币种（默认 USD，V87 新增）。 */
    @Builder.Default
    private String currency = "USD";

    /** 触发本次结算的 outbox 事件 id（溯源，V87 新增）。 */
    private Long sourceEventId;

    /** 挂起/失败原因码（如 COMMISSION_RULE_MISSING / PAYEE_ACCOUNT_MISSING / INVALID_AMOUNT，V87 新增）。 */
    private String reasonCode;

    /** 挂起/失败详情（V87 新增）。 */
    @Column(columnDefinition = "VARCHAR(512)")
    private String failReason;

    /** 重试次数（V87 新增）。 */
    @Builder.Default
    private Integer retryCount = 0;

    /** 结算成功时间（V87 新增）。 */
    private Instant settledAt;

    /** 处理人（人工介入时填写，V87 新增）。 */
    private Long handledBy;

    /** 处理时间（V87 新增）。 */
    private Instant handledAt;

    /** 处理备注（V87 新增）。 */
    @Column(columnDefinition = "VARCHAR(512)")
    private String handleRemark;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
