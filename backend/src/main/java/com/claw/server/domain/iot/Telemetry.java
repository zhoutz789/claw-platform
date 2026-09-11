package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 遥测快照（对应 claw.telemetry，IoT 数字孪生数据源）。
 * 设备（车机/电池 BMS/无人机飞控）上报的最新一次快照；生命周期扫描器的
 * SOH/位置即读自此表，缺失时回退到 battery.soh / 资产年龄。无后端时可由
 * POST /api/v1/telemetry/push 灌 Mock 数据，使「退役→回收」闭环可真实演练。
 */
@Entity
@Table(name = "telemetry", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Telemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long assetId;

    /** 健康度（%），电池/整机通用。 */
    private BigDecimal soh;

    /** SOC（%），与 telemetry_latest 对齐（一致性修复；V101 已加 claw.telemetry.soc 列）。 */
    private BigDecimal soc;

    /** 温度（℃），与 telemetry_latest 对齐（一致性修复；V101 已加 claw.telemetry.temp 列）。 */
    private BigDecimal temp;

    @Column(precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(precision = 10, scale = 7)
    private BigDecimal lng;

    private BigDecimal speedKph;

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
