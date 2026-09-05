package com.claw.server.domain.capacity;

import com.claw.server.common.enums.CapacityPlanStatus;
import com.claw.server.common.enums.CapacityType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 容量预订计划（对应 V71 claw.capacity_plans）。
 * 厂家把一台自有(寄售)资产的可使用产能拆成 total_units 个容量单位对外预订。
 * 资产产权仍为厂家单一主体（CONSIGNED），本实体只描述"使用产能/回佣权"，不持有资产份额。
 */
@Entity
@Table(name = "capacity_plans", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapacityPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    private Long poolEntryId;

    @Column(nullable = false)
    private Long ownerUserId;

    @Column(nullable = false)
    private Integer totalUnits;

    @Builder.Default
    private Integer subscribedUnits = 0;

    @Column(nullable = false)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CapacityType capacityType = CapacityType.SERIAL;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal rebateRate = BigDecimal.valueOf(0.10);

    private Instant windowStart;

    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CapacityPlanStatus status = CapacityPlanStatus.OPEN;

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
