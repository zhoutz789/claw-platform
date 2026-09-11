package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 光伏规范遥测报文（canonical envelope，光伏数据链路切片）。
 *
 * <p>统一单位约定：电压 V、电流 A、功率 W、电量 Wh、温度 ℃、辐照度 W/m²、
 * 频率 Hz、百分比 %、功率因数/效率 无量纲。
 *
 * <p>功率符号约定（在 {@code PvAdapter} 内归一）：
 * <ul>
 *   <li>逆变器（{@code acActivePowerW}）：恒正——逆变器只发电，负号无物理意义，取绝对值。</li>
 *   <li>电表（{@code meterActivePowerW}）：上网（馈网）为正、下网（购电）为负——双向计量必须保留符号。</li>
 * </ul>
 *
 * <p>电量口径：{@code dailyYieldWh} / {@code totalYieldWh}（逆变器侧）与
 * {@code forwardTotalWh} / {@code reverseTotalWh}（电表侧）都是<b>累计计数器</b>读数。
 * 小时电量只由它们的差分得到（见 {@code PvTelemetryService}），绝不用功率积分。
 *
 * <p>所有字段均为可空：不同厂家/机型上报的字段集不同，缺失字段保持 null，落库时跳过。
 * 本类只作数据载体，不做单位换算（换算在 {@code PvAdapter} 内完成）。
 */
public record PvTelemetryReport(

        // 路由身份（GENERIC_MQTT 由报文自带；缺失时由 topic 路径提取）
        String deviceNo,

        /** 报文时间（ISO-8601，如 2026-09-14T08:15:00Z）；缺失时服务端按入站时刻归桶。 */
        String timestamp,

        // —— 运行状态 ——
        /** 逆变器状态：run / standby / fault / shutdown / init。 */
        String inverterState,

        // —— 交流侧（逆变器并网输出）——
        BigDecimal acVoltageA,
        BigDecimal acVoltageB,
        BigDecimal acVoltageC,
        BigDecimal acCurrentA,
        BigDecimal acCurrentB,
        BigDecimal acCurrentC,
        BigDecimal acFrequency,
        /** 有功功率 W（逆变器恒正）。 */
        BigDecimal acActivePowerW,
        /** 无功功率 var（感性/容性以符号区分）。 */
        BigDecimal acReactivePowerVar,
        /** 功率因数 0–1。 */
        BigDecimal powerFactor,

        // —— 直流侧（最多 4 路 MPPT）——
        BigDecimal dcVoltage1,
        BigDecimal dcVoltage2,
        BigDecimal dcVoltage3,
        BigDecimal dcVoltage4,
        BigDecimal dcCurrent1,
        BigDecimal dcCurrent2,
        BigDecimal dcCurrent3,
        BigDecimal dcCurrent4,
        BigDecimal dcPowerW1,
        BigDecimal dcPowerW2,
        BigDecimal dcPowerW3,
        BigDecimal dcPowerW4,

        /** 各组件/组串电流数组 A（路数不定，先 JSON，与 BMS 电芯电压数组同策略）。 */
        List<BigDecimal> stringCurrents,

        // —— 并网点电表（双向计量）——
        /** 有功功率 W（上网为正、下网为负）。 */
        BigDecimal meterActivePowerW,
        /** 正向有功总电能 Wh（上网/馈网累计，METER 来源的电量差分基准）。 */
        BigDecimal forwardTotalWh,
        /** 反向有功总电能 Wh（下网/购电累计）。 */
        BigDecimal reverseTotalWh,
        /** 需量 W。 */
        BigDecimal demandW,

        // —— 气象站 ——
        /** 瞬时辐照度 W/m²。 */
        BigDecimal irradiance,
        /** 组件温度 ℃。 */
        BigDecimal moduleTemp,
        /** 环境温度 ℃。 */
        BigDecimal ambientTemp,
        /** 风速 m/s。 */
        BigDecimal windSpeed,
        /** 日累计辐照量 kWh/m²。 */
        BigDecimal dailyIrradiation,

        // —— 发电量（累计计数器）——
        /** 当日发电量 Wh（厂家计数器，跨日清零）。 */
        BigDecimal dailyYieldWh,
        /** 总发电量 Wh（厂家累计计数器，INVERTER 来源的电量差分基准）。 */
        BigDecimal totalYieldWh,

        // —— 状态/故障 ——
        Integer faultCode,
        /** 限功率百分比 %（0–100）。 */
        BigDecimal deratePercent,
        /** 机内温度 ℃。 */
        BigDecimal internalTemp,
        /** 散热器温度 ℃。 */
        BigDecimal heatsinkTemp,
        /** 转换效率 0–1。 */
        BigDecimal efficiency
) {
}
