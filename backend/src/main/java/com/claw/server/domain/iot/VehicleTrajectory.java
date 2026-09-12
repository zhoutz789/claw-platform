package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 车辆轨迹点（对应 claw.vehicle_trajectory，V123 新增）。
 * 资产级历史轨迹：轨迹回放 / 围栏判定 / 里程退役依据。
 * 列名与迁移脚本 V123 严格对齐；凡字段名以大写字母结尾者均显式 {@code @Column}（ArchUnit 规则），
 * 其余列亦全部显式声明以保证与 DDL 一致。
 */
@Entity
@Table(name = "vehicle_trajectory", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleTrajectory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /** 轨迹点时刻（TIMESTAMPTZ）。 */
    @Column(name = "t", nullable = false)
    private Instant t;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lng")
    private Double lng;

    @Column(name = "speed_kph")
    private Double speedKph;

    @Column(name = "heading")
    private Double heading;

    /** 累计里程（km）——里程退役阈值依据。 */
    @Column(name = "odometer_km", nullable = false)
    private Long odometerKm;

    /** 电量百分比(0-100)。 */
    @Column(name = "soc")
    private Double soc;
}
