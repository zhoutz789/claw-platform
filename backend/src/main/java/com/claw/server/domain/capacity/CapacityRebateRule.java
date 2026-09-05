package com.claw.server.domain.capacity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 回佣规则（对应 V71 claw.capacity_rebate_rules）。
 * 计划级回佣配置；支持未来多档扩展，现单档。
 */
@Entity
@Table(name = "capacity_rebate_rules", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapacityRebateRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long planId;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal rebateRate = BigDecimal.valueOf(0.10);

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal minPayout = BigDecimal.valueOf(0.01);

    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

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
