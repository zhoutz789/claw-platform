package com.claw.server.domain.station;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 充电会话（锂电池 BMS 对接方案 Phase D，对齐方案 §6「充电」）。
 *
 * <p>记录一次充电的起止、电量、峰值功率与计费；电量以 BMS 累计 Wh 计数器为准
 * （与换电结算同一计量铁律，禁用 SOC% 差值）。计费复用既有 {@code FeeRule} /
 * {@code ElecPriceSnapshot} 电价与统一服务费口径（0.1–0.2 USD，平台配置）。
 */
@Entity
@Table(name = "charge_sessions", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChargeSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 充电发生站点（家充/第三方桩可为空）。 */
    private Long stationId;

    /** 被充电的电池资产 id。 */
    @Column(nullable = false)
    private Long assetId;

    /** 充电桩设备编号（Device.deviceNo，device_type=CHARGER）。 */
    @Column(length = 64)
    private String deviceNo;

    /** ACTIVE | ENDED。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(nullable = false)
    @Builder.Default
    private Instant startedAt = Instant.now();

    private Instant endedAt;

    /** 会话开始时 BMS 净能量（Σcharge_wh − Σdischarge_wh），Wh。 */
    private BigDecimal startEnergyWh;

    /** 会话结束时 BMS 净能量，Wh。 */
    private BigDecimal endEnergyWh;

    /** 本次实际送达电量（endEnergyWh − startEnergyWh），Wh。 */
    private BigDecimal energyDeliveredWh;

    /** 会话内峰值功率 W。 */
    @Column(name = "peak_power_w")
    private BigDecimal peakPowerW;

    /** 电价快照 USD/Wh（来自 ElecPriceSnapshot，含电网/光伏与 TOU 时段）。 */
    private BigDecimal pricePerWh;

    private BigDecimal electricityFee;

    private BigDecimal serviceFee;

    private BigDecimal totalFee;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
