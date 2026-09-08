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

    /**
     * 资产（老流程按资产建计划；V81 起改为按商品建计划，故此列可空）。
     *
     * <p>V81 已将该列在库上去掉 NOT NULL：新入口是商品详情页的「容量预定」按钮，
     * 计划挂在 {@link #productId} 上，建计划时并不要求先绑定资产。
     */
    private Long assetId;

    /**
     * 关联商品（claw.products.id，V81 新增）。
     *
     * <p>容量预定入口挂在厂家发布的商品上：前端点「容量预定」按钮直接带 productId 进出，
     * 计划与商品联动、不可手改，避免出现「计划挂在 A 商品、订单却来自 B 商品」的错配。
     */
    private Long productId;

    private Long poolEntryId;

    /**
     * 计划说明（V81 新增，TEXT）：风险提示 + 操作方法。
     *
     * <p>厂家建计划时填一次，客户侧只读展示 —— 同一计划对所有客户说法一致，
     * 不在前端各写一份、也不允许订户改写。
     */
    @Column(name = "plan_desc", columnDefinition = "text")
    private String planDesc;

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
