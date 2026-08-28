package com.claw.server.domain.iot;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 最新遥测（对应 claw.telemetry_latest，V8 表）。
 * Redis 热数据 + PG 落库；每个设备仅一条最新记录。
 */
@Entity
@Table(name = "telemetry_latest", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TelemetryLatest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long deviceId;

    @Column(nullable = false)
    private Long assetId;

    private BigDecimal speed;   // km/h

    private BigDecimal soc;     // %

    private BigDecimal soh;     // 健康度 %（电池/整机通用，驱动退役生命周期）

    private BigDecimal temp;    // ℃

    private BigDecimal humid;   // %

    /** 故障码列表（JSON）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String faults;

    private BigDecimal lat;

    private BigDecimal lng;

    // —— 车辆终端契约扩展字段（兼容老 BMS 字段 soc/soh/humid/faults）——
    private Integer acc;               // 点火状态 0=熄火 1=点火
    private BigDecimal batteryVoltage; // 电瓶电压 V
    private Integer rssi;              // 信号强度 dBm
    private BigDecimal course;         // 方向角
    private BigDecimal altitude;       // 海拔 m
    private Integer relayState;        // 继电器状态 0/1
    private Integer doorState;         // 门磁 0/1
    private Integer vibState;          // 震动 0/1
    /** 告警列表（JSON）：power_off / tamper / geo_fence / low_batt / vib。 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String alarms;

    @Column(nullable = false)
    @Builder.Default
    private Instant reportedAt = Instant.now();

    @Builder.Default
    private Long tenantId = 1L;
}
