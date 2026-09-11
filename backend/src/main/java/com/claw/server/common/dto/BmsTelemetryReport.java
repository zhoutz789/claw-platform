package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * BMS 规范遥测报文（canonical envelope，锂电池 BMS 对接方案 Phase A）。
 *
 * <p>统一单位约定：电压 V、电流 A（放电为正/充电为负，按规范统一）、温度 ℃、SOC/SOH %、
 * 容量 Ah、能量 kWh。线下多品牌协议（GB/T 27930、Pylontech、通用 Modbus）经边缘网关
 * {@code BmsAdapter} 归一化为此结构后再经 {@code claw/iot/{deviceNo}/telemetry} 上报。
 *
 * <p>所有字段均为可空：不同保护板/协议上报的字段集不同，缺失字段保持 null，落库时跳过。
 * 本类只作数据载体，不做单位换算（换算在 {@code BmsAdapter} 内完成）。
 */
public record BmsTelemetryReport(

        // 路由身份（GENERIC_MQTT 由报文自带；缺失时由 topic 路径提取）
        String deviceNo,

        // 核心状态（telemetry_latest 已有标量，BMS 一并上报以归一化）
        BigDecimal soc,
        BigDecimal soh,

        // 2.1 身份与固件
        String bmsSn,
        String packSn,
        String manufacturer,
        String firmwareVersion,
        String protocolVersion,
        String chemistry,
        String productionDate,

        // 2.2 额定规格
        BigDecimal nominalVoltage,
        BigDecimal capacityAh,
        BigDecimal capacityKwh,
        Integer cellSeries,
        Integer cellParallel,
        String cellConfig,
        BigDecimal ratedPowerW,
        BigDecimal maxChargeCurrentA,
        BigDecimal maxDischargeCurrentA,
        BigDecimal chargeVoltageLimit,
        BigDecimal dischargeVoltageLimit,
        Integer cycleLifeDesign,

        // 2.3 电压
        BigDecimal packVoltage,
        BigDecimal moduleVoltage,
        BigDecimal busVoltage,
        BigDecimal totalVoltage,
        List<BigDecimal> cellVoltages,
        BigDecimal cellVoltageMax,
        BigDecimal cellVoltageMin,
        Integer cellVoltageMaxId,
        Integer cellVoltageMinId,

        // 2.4 电流功率
        BigDecimal currentA,
        BigDecimal powerW,
        BigDecimal ccl,
        BigDecimal dcl,
        BigDecimal cvl,

        // 2.5 容量/能量
        BigDecimal remainingCapacityAh,
        BigDecimal fullChargeCapacityAh,
        BigDecimal remainingEnergyKwh,
        BigDecimal fullChargeEnergyKwh,

        // 2.6 温度
        List<BigDecimal> temperatures,
        BigDecimal tempMax,
        BigDecimal tempMin,
        BigDecimal tempAvg,
        Integer tempMaxId,
        Integer tempMinId,
        BigDecimal mosTemp,
        BigDecimal envTemp,

        // 2.7 保护/告警
        Boolean protectOverVoltage,
        Boolean protectUnderVoltage,
        Boolean protectOverCurrent,
        Boolean protectShortCircuit,
        Boolean protectOverTemp,
        Boolean protectUnderTemp,
        Boolean protectCellImbalance,
        Boolean protectCommLoss,
        Boolean protectMosfault,
        String faultCode,
        BigDecimal insulationResistance,

        // 2.8 均衡
        String balanceStatus,
        BigDecimal balanceCurrent,
        BigDecimal cellVoltageSpread,

        // 2.9 开关/继电器
        Boolean chargeEnable,
        Boolean dischargeEnable,
        Boolean mainContactor,
        Boolean prechargeRelay,
        Integer relayState,

        // 2.10 热管理（冷季加热 / 热季水冷）
        Boolean heaterEnable,
        BigDecimal heaterTargetTemp,
        Boolean waterCoolingEnable,
        Boolean coolingPumpStatus,
        Integer fanSpeed,
        BigDecimal coolantTempIn,
        BigDecimal coolantTempOut,

        // 2.11 定位/物流
        BigDecimal lat,
        BigDecimal lng,
        BigDecimal altitude,
        BigDecimal speed,
        Integer satelliteCount,
        String lastFixTime,

        // 2.12 计量/状态
        BigDecimal cumulativeChargeAh,
        BigDecimal cumulativeChargeKwh,
        BigDecimal cumulativeDischargeAh,
        BigDecimal cumulativeDischargeKwh,
        String bmsState,
        String reportedAt
) {
}
