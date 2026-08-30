package com.claw.server.domain.commission;

import com.claw.server.common.enums.CommissionType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 设备销售提成规则（对应 V51 claw.device_sales_commission_rules，与光伏 revenue_split_rules 解耦）。
 * NULL manufacturer_id = 平台默认规则；NULL product_id = 该厂全部商品；多规则命中取高 priority。
 */
@Entity
@Table(name = "device_sales_commission_rules", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceSalesCommissionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long manufacturerId;

    private Long productId;

    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommissionType commissionType;

    @Builder.Default
    private BigDecimal rate = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal amount = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal minAmount = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal maxAmount = BigDecimal.ZERO;

    @Builder.Default
    private Integer priority = 0;

    private Instant effectiveFrom;

    private Instant effectiveTo;

    @Builder.Default
    private Boolean enabled = true;

    private Long createdBy;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
