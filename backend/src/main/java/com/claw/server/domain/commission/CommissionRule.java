package com.claw.server.domain.commission;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 设备销售提成规则（对应 V51 claw.device_sales_commission_rules，R7）。
 * 与光伏分成规则 revenue_split_rules 完全解耦，单独维护。
 * 命中规则优先级取高（priority 大者优先）；commission_type ∈ {RATE 比例, AMOUNT 定额}。
 */
@Entity
@Table(name = "device_sales_commission_rules", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommissionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL=平台默认规则；否则绑定具体厂家。 */
    @Column(name = "manufacturer_id")
    private Long manufacturerId;

    /** NULL=该厂家全部商品；否则绑定具体商品。 */
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "rule_name", length = 80)
    private String ruleName;

    /** RATE（比例）/ AMOUNT（定额）。 */
    @Column(name = "commission_type", nullable = false, length = 20)
    private String commissionType;

    /** 比例（如 0.08）。 */
    @Column(precision = 6, scale = 4)
    private BigDecimal rate;

    /** 定额。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "min_amount", precision = 12, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", precision = 12, scale = 2)
    private BigDecimal maxAmount;

    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** 用包装类型 Boolean：primitive boolean 会让 Lombok 生成 isEnabled() 而非 getEnabled()，与调用方语义不符。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
