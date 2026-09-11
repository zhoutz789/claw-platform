package com.claw.server.domain.asset;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 电池扩展（对应 claw.batteries）。
 * v2.0：deposit_value = 固定30%押金（取消动态残值概念，D36 定稿）。
 * SOH 仍持续追踪用于残值评估（D41 残值回收），但不再驱动押金金额。
 */
@Entity
@Table(name = "batteries", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Battery {

    @Id
    @Column(name = "asset_id")
    private Long assetId;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private BigDecimal capacityKwh;

    private String protocolVer;

    // —— 锂电池 BMS 对接方案 Phase A：组级规格字段（V100__battery_specs）——
    /** 化学体系：LFP / NMC / LTO。 */
    @Column(length = 16)
    private String chemistry;

    /** 标称电压 V。 */
    private BigDecimal nominalVoltage;

    /** 标称容量 Ah。 */
    private BigDecimal capacityAh;

    /** 串联电芯数。 */
    private Integer cellSeries;

    /** 并联电芯数。 */
    private Integer cellParallel;

    /** 串并联配置描述，如 "16S1P"。 */
    @Column(length = 32)
    private String cellConfig;

    /** 额定功率 W。 */
    @Column(name = "rated_power_w")
    private BigDecimal ratedPowerW;

    /** 设计最大充电电流 A（CCL，实时值走遥测）。 */
    @Column(name = "max_charge_current_a")
    private BigDecimal maxChargeCurrentA;

    /** 设计最大放电电流 A（DCL，实时值走遥测）。 */
    @Column(name = "max_discharge_current_a")
    private BigDecimal maxDischargeCurrentA;

    /** 充电电压上限 V（CVL）。 */
    private BigDecimal chargeVoltageLimit;

    /** 放电电压下限 V。 */
    private BigDecimal dischargeVoltageLimit;

    /** 温度探头数量（决定遥测温度数组长度）。 */
    private Integer tempProbeCount;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal soh = BigDecimal.valueOf(100.00);   // 健康度 %（用于残值评估，不再驱动押金）

    @Builder.Default
    private Integer cycleCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal depositValue = BigDecimal.ZERO;     // 押金（固定30%，平台设定，D36）

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
