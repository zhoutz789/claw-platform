package com.claw.server.domain.swap;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 锁版费率（对应 claw.fee_rules，V6 表）。
 * 按度计价：电费（光伏/市电）+ 换电服务费三拆（基金/站/平台）。
 */
@Entity
@Table(name = "fee_rules", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
public class FeeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PV_ELEC / GRID_ELEC / SWAP_SERVICE。 */
    @Column(name = "rule_code", nullable = false, unique = true)
    private String ruleCode;

    private String name;

    private String unit;

    /** $/kWh 锁版价。 */
    private BigDecimal price;

    /** 分账拆解 JSON。 */
    private String shareJson;

    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;

    private String status;

    private Instant createdAt;
    private Instant updatedAt;
}
