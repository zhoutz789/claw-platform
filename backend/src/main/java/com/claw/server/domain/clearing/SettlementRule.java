package com.claw.server.domain.clearing;

import com.claw.server.common.enums.RuleBasis;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 分账规则（对应 claw.settlement_rule，V129）。
 *
 * <p>统一分账引擎（{@code SplitEngine}）的配置源，替代散落各域的分成逻辑。
 * 命中口径：{@code bizScene + payeeType(+manufacturerId) + 生效期 + status=ACTIVE}，
 * 按 {@link #priority} 升序（越小越先扣）参与分账。
 *
 * <p>幂等：{@code (bizScene, payeeType, ruleVersion)} 唯一。
 */
@Entity
@Table(name = "settlement_rule", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 业务场景：CONSIGNMENT_SCAN/RENTAL_SPLIT/TASK_SETTLE/CAPACITY/DEPOSIT/RECOVERY。 */
    @Column(name = "biz_scene", nullable = false, length = 24)
    private String bizScene;

    /** 收款方类型：MANUFACTURER/STATION/PLATFORM/INSURANCE/LOGISTICS/OWNER。 */
    @Column(name = "payee_type", nullable = false, length = 24)
    private String payeeType;

    /** 计价基准：RATE / FIXED / TIER / RESIDUAL（残差兜底，V132 起显式化）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "basis", nullable = false, length = 16)
    private RuleBasis basis;

    /** basis=RATE 时的比例（如 0.05 = 5%）。 */
    @Column(name = "rate")
    private BigDecimal rate;

    /** basis=FIXED 时的固定金额。 */
    @Column(name = "fixed_amount")
    private BigDecimal fixedAmount;

    /** basis=TIER 时的阶梯结构（jsonb 文本）。 */
    @Column(name = "tier_json")
    private String tierJson;

    /** 优先级：越小越先扣（平台服务费通常最先）。 */
    @Builder.Default
    @Column(name = "priority", nullable = false)
    private Integer priority = 10;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    /** 结算周期：T+0/T+1/T+7。 */
    @Builder.Default
    @Column(name = "settle_cycle", nullable = false, length = 8)
    private String settleCycle = "T+0";

    /** 可选：按厂家细分规则。 */
    @Column(name = "manufacturer_id")
    private Long manufacturerId;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Builder.Default
    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion = 1;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Builder.Default
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Builder.Default
    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
