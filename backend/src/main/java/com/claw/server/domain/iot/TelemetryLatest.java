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

    // —— 锂电池 BMS 实时遥测（V101__telemetry_bms，Phase A）——
    private BigDecimal packVoltage;       // 组电压 V
    // 以下两个 BMS 字段同样以大写字母结尾，按 ArchitectureBoundaryTest 规则显式声明列名
    // （与 V101__telemetry_bms 的 current_a / power_w 对齐）。
    @Column(name = "current_a")
    private BigDecimal currentA;          // 电流 A（放电正/充电负，按规范统一）
    @Column(name = "power_w")
    private BigDecimal powerW;            // 功率 W
    private BigDecimal ccl;               // 实时充电电流限值 A
    private BigDecimal dcl;               // 实时放电电流限值 A
    private BigDecimal cvl;               // 实时充电电压限值 V
    private BigDecimal remainingCapacityAh;
    private BigDecimal fullChargeCapacityAh;
    private BigDecimal tempMax;
    private BigDecimal tempMin;
    private Integer tempMaxId;
    private Integer tempMinId;
    /** 各温度探头数组（JSON）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String temperaturesJson;
    /** 各电芯电压数组（JSON）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String cellVoltagesJson;
    private String balanceStatus;         // passive/active/none
    private BigDecimal balanceCurrent;
    private BigDecimal cellVoltageSpread; // ΔV mV
    private Boolean chargeEnable;
    private Boolean dischargeEnable;
    private Boolean heaterEnable;
    private Boolean waterCoolingEnable;
    private Integer fanSpeed;             // 0-100%
    private BigDecimal coolantTempIn;
    private BigDecimal coolantTempOut;
    private Integer satelliteCount;
    private java.time.Instant lastFixTime;
    private String bmsState;              // idle/charging/discharging/fault/protect

    // —— 光伏实时遥测（V104__telemetry_pv，光伏数据链路切片）——
    // 单位：电压 V、电流 A、功率 W、电量 Wh、温度 ℃、辐照度 W/m²、频率 Hz、百分比 %
    // 交流侧（逆变器并网输出）
    // 注：以下以大写字母结尾的字段必须显式声明 @Column，否则 Hibernate 隐式命名会推成全小写
    // （acVoltageA → acvoltagea），与 Flyway 下划线列名不一致（ArchitectureBoundaryTest 强制）。
    @Column(name = "ac_voltage_a")
    private BigDecimal acVoltageA;
    @Column(name = "ac_voltage_b")
    private BigDecimal acVoltageB;
    @Column(name = "ac_voltage_c")
    private BigDecimal acVoltageC;
    @Column(name = "ac_current_a")
    private BigDecimal acCurrentA;
    @Column(name = "ac_current_b")
    private BigDecimal acCurrentB;
    @Column(name = "ac_current_c")
    private BigDecimal acCurrentC;
    private BigDecimal acFrequency;
    /** 交流有功功率 W（逆变器恒正）。 */
    @Column(name = "ac_active_power_w")
    private BigDecimal acActivePowerW;
    /** 交流无功功率 var。 */
    private BigDecimal acReactivePowerVar;
    /** 功率因数 0–1。 */
    private BigDecimal powerFactor;

    // 直流侧（最多 4 路 MPPT）
    private BigDecimal dcVoltage1;
    private BigDecimal dcVoltage2;
    private BigDecimal dcVoltage3;
    private BigDecimal dcVoltage4;
    private BigDecimal dcCurrent1;
    private BigDecimal dcCurrent2;
    private BigDecimal dcCurrent3;
    private BigDecimal dcCurrent4;
    private BigDecimal dcPowerW1;
    private BigDecimal dcPowerW2;
    private BigDecimal dcPowerW3;
    private BigDecimal dcPowerW4;
    /** 各组件/组串电流数组（JSON）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String stringCurrentsJson;

    // 并网点电表（双向计量：forward=上网/馈网，reverse=下网/购电）
    /** 电表有功功率 W（上网为正、下网为负）。 */
    @Column(name = "meter_active_power_w")
    private BigDecimal meterActivePowerW;
    /** 正向有功总电能 Wh（METER 来源小时电量的差分基准）。 */
    private BigDecimal forwardTotalWh;
    /** 反向有功总电能 Wh。 */
    private BigDecimal reverseTotalWh;
    /** 需量 W。 */
    @Column(name = "demand_w")
    private BigDecimal demandW;

    // 气象站
    /** 瞬时辐照度 W/m²。 */
    private BigDecimal irradiance;
    /** 组件温度 ℃。 */
    private BigDecimal moduleTemp;
    /** 环境温度 ℃。 */
    private BigDecimal ambientTemp;
    /** 风速 m/s。 */
    private BigDecimal windSpeed;
    /** 日累计辐照量 kWh/m²。 */
    private BigDecimal dailyIrradiation;

    // 发电量累计计数器（小时电量只由它们的差分得到，绝不功率积分）
    private BigDecimal dailyYieldWh;
    private BigDecimal totalYieldWh;

    // 运行状态
    private String inverterState;         // run/standby/fault/shutdown/init
    private Integer faultCode;
    private BigDecimal deratePercent;     // 限功率百分比 %
    private BigDecimal internalTemp;
    private BigDecimal heatsinkTemp;
    private BigDecimal efficiency;        // 转换效率 0–1

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
