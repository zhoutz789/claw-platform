package com.claw.server.domain.asset;

import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 资产主表（对应 claw.assets）。
 * 车辆/电池/充电桩/光伏电站统一抽象，差异字段进各自扩展表（vehicles / batteries）。
 * 状态机流转由 {@code AssetStateMachine} 约束，每次变更写入 {@code AssetStatusLog} 审计。
 */
@Entity
@Table(name = "assets", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetType assetType;

    /**
     * 使用模式（Phase D）：SWAP=参与换电市场（默认）；STORAGE=被能源调度临时借调为储能。
     * 仅对 BATTERY 有意义；ENERGY_STORAGE 资产恒为 STORAGE。与资产类型解耦，切换不触发类型变更。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "usage_mode", length = 16)
    @Builder.Default
    private com.claw.server.common.enums.AssetUsageMode usageMode =
            com.claw.server.common.enums.AssetUsageMode.SWAP;

    @Column(nullable = false, unique = true)
    private String assetNo;

    @Column(unique = true)
    private String qrCode;

    private Long manufacturerId; // 厂家
    private Long productId;      // 商品
    private Long skuId;          // SKU
    private String serialNumber; // 序列号（出厂）

    /** 订单项溯源（V38：资产从哪个订单项登记生成；非订单资产为 null）。 */
    @Column(name = "order_item_id")
    private Long orderItemId;

    /** 当前主部件编号快照（V38：JSON 数组，结构见设计 §2.4；随更换更新）。 */
    @Column(name = "component_nos_json")
    private String componentNosJson;

    private Long ownerId;   // 管理人
    private Long userId;    // 当前使用人

    /** 资产能力标签（CSV：LOGISTICS,RIDE_HAIL,TAXI,AD_DISPLAY,DRONE_OP,SWAP）。V94 引入，接单校验用。 */
    @Column(name = "capabilities")
    private String capabilities;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AssetStatus status = AssetStatus.IN_STOCK;

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
