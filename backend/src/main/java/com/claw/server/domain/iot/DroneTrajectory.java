package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 无人机航迹点（对应 claw.drone_trajectory，V138 新增）。
 * 资产级历史航迹：轨迹回放 / 围栏判定 / 作业计量依据。
 * 镜像 {@link VehicleTrajectory}，新增航空维度 alt_m / speed_mps / battery_pct / pos_mode / flight_no。
 * 列名与迁移脚本 V138 严格对齐；凡字段名以大写字母结尾者均显式 {@code @Column}（ArchUnit 规则），
 * 其余列亦全部显式声明以保证与 DDL 一致。
 */
@Entity
@Table(name = "drone_trajectory", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DroneTrajectory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /** 航迹点时刻（TIMESTAMPTZ）。 */
    @Column(name = "ts", nullable = false)
    private Instant ts;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lng")
    private Double lng;

    /** 相对高度（米）。字段名结尾为大写 M，必须显式列名 alt_m。 */
    @Column(name = "alt_m")
    private Double altM;

    /** 速度（米/秒）。 */
    @Column(name = "speed_mps")
    private Double speedMps;

    /** 航向角（度）。 */
    @Column(name = "heading")
    private Double heading;

    /** 电量百分比(0-100)。 */
    @Column(name = "battery_pct")
    private Double batteryPct;

    /** 定位模式：RTK / PPK / GNSS（链路断即降级）。 */
    @Column(name = "pos_mode")
    private String posMode;

    /** 上报来源（FCU / RTK / EDGE）。 */
    @Column(name = "source")
    private String source;

    /** 架次号。 */
    @Column(name = "flight_no")
    private String flightNo;
}
