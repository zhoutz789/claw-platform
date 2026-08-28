package com.claw.server.domain.asset;

import jakarta.persistence.*;
import com.claw.server.common.enums.DronePayloadType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 无人机扩展（对应 claw.drones）。
 * 航空资产专属属性：Remote ID 广播码、续航、载荷、适航证、飞手资质。
 * 复用 P0 的资产状态机 / 绑定 / 生命周期扫描器，仅在本实体承载航空差异化字段。
 */
@Entity
@Table(name = "drones", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Drone {

    @Id
    @Column(name = "asset_id")
    private Long assetId;

    /** Remote ID 广播码（合规必填，类比车辆 VIN）。 */
    @Column(nullable = false, unique = true)
    private String remoteId;

    @Column(nullable = false)
    private String model;

    /** 单电最大续航（分钟）。 */
    private Integer maxFlightTimeMin;

    /** 最大载荷（kg）。 */
    private BigDecimal maxPayloadKg;

    /** 载荷类型：SPRAY 植保 / CARGO 物流 / SLING 吊运 / THERMAL 巡检热成像 / RECON 测绘。 */
    @Enumerated(EnumType.STRING)
    private DronePayloadType payloadType;

    /** SSCA 适航证号（柬埔寨民航局）。 */
    private String airworthinessCertNo;

    /** 默认飞手资质号（可空，按飞行计划另行指定）。 */
    private String pilotLicenseNo;

    /** 累计飞行时长（分钟）—— 生命周期扫描器据其退役。 */
    private Long flightMinutes;

    private String protocolVer;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
