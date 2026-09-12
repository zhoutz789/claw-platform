package com.claw.server.domain.pv;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 光伏日对账结果（对应 claw.pv_daily_reconciliation，V115 表）。
 *
 * <p><b>口径：</b>全站 {@code INVERTER} 当日 {@code energy_wh} 之和，与全站 {@code METER}
 * 当日 {@code energy_wh} 之和直接比较（两者都是小时发电量差分，口径已统一，不做二次差分）。
 * {@code deviationWh = inverter − meter}；{@code deviationRate = deviation / meter}
 * （meter 为 0 记 null，避免除零）。
 *
 * <p><b>状态：</b>{@code OK} 偏差率≤阈值 / {@code WARN} 超阈值 / {@code GAP} 任一侧缺数据无法算。
 *
 * <p><b>幂等：</b>唯一索引 {@code (station_asset_id, day)}，同一电站同一天重复对账命中同一行。
 */
@Entity
@Table(
        name = "pv_daily_reconciliation",
        schema = "claw",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pv_daily_reconciliation_station_day",
                columnNames = {"station_asset_id", "day"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PvDailyReconciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属光伏站资产。 */
    @Column(name = "station_asset_id", nullable = false)
    private Long stationAssetId;

    /** 对账日（UTC 日期，与 pv_generation_hourly.bucket_at 的 UTC 截断口径一致）。 */
    @Column(name = "day", nullable = false)
    private LocalDate day;

    /** 全站 INVERTER 当日 energy_wh 合计 Wh。 */
    @Column(name = "inverter_energy_wh", precision = 18, scale = 4)
    private BigDecimal inverterEnergyWh;

    /** 全站 METER 当日 energy_wh 合计 Wh（下网=上网计量）。 */
    @Column(name = "meter_reverse_energy_wh", precision = 18, scale = 4)
    private BigDecimal meterReverseEnergyWh;

    /** deviation_wh = inverter − meter；GAP 时记 null。 */
    @Column(name = "deviation_wh", precision = 18, scale = 4)
    private BigDecimal deviationWh;

    /** deviation_rate = deviation / meter；meter 为 0 时记 null。 */
    @Column(name = "deviation_rate", precision = 6, scale = 4)
    private BigDecimal deviationRate;

    /** OK 偏差率≤阈值 / WARN 超阈值 / GAP 缺数据无法算。 */
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "OK";

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    /** 首次对账时间（更新时保留初值，不刷新）。 */
    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
