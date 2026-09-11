package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 光伏小时发电量（对应 claw.pv_generation_hourly，V105 表）。
 *
 * <p><b>计量铁律：</b>{@code energyWh} 只由「累计计数器差分」得到——
 * INVERTER 来源取 {@code total_yield_wh}，METER 来源取 {@code forward_total_wh}，
 * <b>绝不用功率对时间积分</b>（功率采样有丢包/抖动，积分会系统性偏移）。
 *
 * <p><b>双来源隔离：</b>{@code source} 为 INVERTER 与 METER 时各占一行（唯一索引含 source），
 * 两者是不同物理口径（逆变器计发电、电表计上网），永不混算。
 *
 * <p><b>幂等：</b>唯一索引 {@code (device_no, bucket_at, source)}，同一小时重复上报命中同一行；
 * 又因差分以本行已存的 {@code cumulativeWh} 为基准，重报同一表底读数差分为 0，不会累加。
 * {@code cumulativeWh} 同时是审计锚点：可回溯任意时刻的表底读数。
 */
@Entity
@Table(
        name = "pv_generation_hourly",
        schema = "claw",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pv_generation_hourly_device_bucket_source",
                columnNames = {"device_no", "bucket_at", "source"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PvGenerationHourly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属光伏站资产（可空：设备未绑定资产时先记设备维度）。 */
    @Column(name = "station_asset_id")
    private Long stationAssetId;

    @Column(name = "device_no", nullable = false, length = 64)
    private String deviceNo;

    /** INVERTER / METER（两来源永不可混算）。 */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /** 小时桶起点（整点，UTC 截断）。 */
    @Column(name = "bucket_at", nullable = false)
    private Instant bucketAt;

    /** 该小时电量 Wh（累计计数器差分得到）。 */
    @Column(name = "energy_wh", precision = 18, scale = 4)
    private BigDecimal energyWh;

    /** 该时刻累计计数器读数 Wh（表底，审计锚点）。 */
    @Column(name = "cumulative_wh", precision = 18, scale = 4)
    private BigDecimal cumulativeWh;

    /** 该小时峰值功率 W。 */
    @Column(name = "peak_power_w", precision = 11, scale = 2)
    private BigDecimal peakPowerW;

    /** 该小时辐照度 W/m²（本切片取该小时最后一次上报值，多采样加权均值在后续切片补齐）。 */
    @Column(name = "irradiance_avg", precision = 10, scale = 2)
    private BigDecimal irradianceAvg;

    /**
     * 性能比 PR = 实际发电量 / (装机容量 × 峰值日照时数)。
     * 需要资产铭牌装机容量（本切片尚无该输入），故留空，待后续切片接入后计算。
     */
    @Column(name = "pr", precision = 6, scale = 4)
    private BigDecimal pr;

    @Builder.Default
    private Long tenantId = 1L;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
