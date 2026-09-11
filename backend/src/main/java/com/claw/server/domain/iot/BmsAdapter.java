package com.claw.server.domain.iot;

import com.claw.server.common.dto.BmsTelemetryReport;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 边缘网关 BMS 协议适配层（锂电池 BMS 对接方案 Phase A）。
 *
 * <p>职责：把线下保护板/换电柜/充电桩经物理协议上报的原始报文，归一化为
 * {@link BmsTelemetryReport} 规范结构（统一单位、处理厂商口径差异），再经
 * {@code claw/iot/{deviceNo}/telemetry} 上行。
 *
 * <p>多 profile 可插拔：新增品牌只加一个 profile 分支。当前实现：
 * <ul>
 *   <li>{@code GENERIC_MQTT}：报文已是规范 JSON（边缘网关固件完成 CAN/RS485→MQTT 归一化），
 *       后端仅做字段映射 + 单位守卫（SOC 0–255→0–100 等），无重解析。</li>
 *   <li>{@code PYLON_CAN} / {@code PYLON_MODBUS} / {@code GBT27930} / {@code GENERIC_MODBUS}：
 *       物理协议解析（CAN 0x351/0x355/0x356、Modbus 寄存器、GB/T 27930 报文）由边缘网关固件完成；
 *       按"先后端、后固件"决策，后端 Phase A 仅接收归一化后的 GENERIC_MQTT，故此处显式拒绝并说明归属。</li>
 * </ul>
 */
@Component
@Slf4j
public class BmsAdapter {

    public enum Profile {
        GENERIC_MQTT,
        PYLON_CAN,
        PYLON_MODBUS,
        GBT27930,
        GENERIC_MODBUS
    }

    /**
     * 把原始报文按 profile 归一化为规范结构。
     *
     * @throws UnsupportedOperationException 物理协议 profile 尚未在后端实现（改由固件侧归一化）
     */
    public BmsTelemetryReport normalize(JsonNode raw, Profile profile) {
        return switch (profile) {
            case GENERIC_MQTT -> fromGenericMqtt(raw);
            case PYLON_CAN, PYLON_MODBUS, GBT27930, GENERIC_MODBUS ->
                    throw new UnsupportedOperationException(
                            "物理协议 " + profile + " 的解析由边缘网关固件（CAN/RS485→MQTT）完成；" +
                                    "后端 Phase A 仅接收归一化后的 GENERIC_MQTT 报文（先后端、后固件）。");
        };
    }

    /** GENERIC_MQTT：报文即规范 JSON，做字段映射与单位守卫。 */
    private BmsTelemetryReport fromGenericMqtt(JsonNode n) {
        // SOC/SOH 守卫：若上游以 0–255 上报，归一到 0–100
        BigDecimal soc = guardPercent(dec(n, "soc"));
        BigDecimal soh = guardPercent(dec(n, "soh"));

        return new BmsTelemetryReport(
                str(n, "deviceNo"),
                soc, soh,
                str(n, "bmsSn"), str(n, "packSn"), str(n, "manufacturer"),
                str(n, "firmwareVersion"), str(n, "protocolVersion"), str(n, "chemistry"), str(n, "productionDate"),

                dec(n, "nominalVoltage"), dec(n, "capacityAh"), dec(n, "capacityKwh"),
                intOf(n, "cellSeries"), intOf(n, "cellParallel"), str(n, "cellConfig"),
                dec(n, "ratedPowerW"), dec(n, "maxChargeCurrentA"), dec(n, "maxDischargeCurrentA"),
                dec(n, "chargeVoltageLimit"), dec(n, "dischargeVoltageLimit"), intOf(n, "cycleLifeDesign"),

                dec(n, "packVoltage"), dec(n, "moduleVoltage"), dec(n, "busVoltage"), dec(n, "totalVoltage"),
                listDec(n, "cellVoltages"), dec(n, "cellVoltageMax"), dec(n, "cellVoltageMin"),
                intOf(n, "cellVoltageMaxId"), intOf(n, "cellVoltageMinId"),

                dec(n, "currentA"), dec(n, "powerW"), dec(n, "ccl"), dec(n, "dcl"), dec(n, "cvl"),

                dec(n, "remainingCapacityAh"), dec(n, "fullChargeCapacityAh"),
                dec(n, "remainingEnergyKwh"), dec(n, "fullChargeEnergyKwh"),

                listDec(n, "temperatures"), dec(n, "tempMax"), dec(n, "tempMin"), dec(n, "tempAvg"),
                intOf(n, "tempMaxId"), intOf(n, "tempMinId"), dec(n, "mosTemp"), dec(n, "envTemp"),

                bool(n, "protectOverVoltage"), bool(n, "protectUnderVoltage"), bool(n, "protectOverCurrent"),
                bool(n, "protectShortCircuit"), bool(n, "protectOverTemp"), bool(n, "protectUnderTemp"),
                bool(n, "protectCellImbalance"), bool(n, "protectCommLoss"), bool(n, "protectMosfault"),
                str(n, "faultCode"), dec(n, "insulationResistance"),

                str(n, "balanceStatus"), dec(n, "balanceCurrent"), dec(n, "cellVoltageSpread"),

                bool(n, "chargeEnable"), bool(n, "dischargeEnable"), bool(n, "mainContactor"),
                bool(n, "prechargeRelay"), intOf(n, "relayState"),

                bool(n, "heaterEnable"), dec(n, "heaterTargetTemp"), bool(n, "waterCoolingEnable"),
                bool(n, "coolingPumpStatus"), intOf(n, "fanSpeed"), dec(n, "coolantTempIn"), dec(n, "coolantTempOut"),

                dec(n, "lat"), dec(n, "lng"), dec(n, "altitude"), dec(n, "speed"), intOf(n, "satelliteCount"),
                str(n, "lastFixTime"),

                dec(n, "cumulativeChargeAh"), dec(n, "cumulativeChargeKwh"),
                dec(n, "cumulativeDischargeAh"), dec(n, "cumulativeDischargeKwh"),
                str(n, "bmsState"), str(n, "reportedAt")
        );
    }

    /** SOC/SOH 守卫：>100 视为 0–255 标度，归一到 0–100。 */
    private BigDecimal guardPercent(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.valueOf(100)) > 0) {
            return v.divide(BigDecimal.valueOf(255), 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
        return v;
    }

    private String str(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private BigDecimal dec(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).decimalValue() : null;
    }

    private Boolean bool(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asBoolean() : null;
    }

    private Integer intOf(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asInt() : null;
    }

    private List<BigDecimal> listDec(JsonNode n, String f) {
        if (!n.hasNonNull(f) || !n.get(f).isArray()) return null;
        List<BigDecimal> out = new ArrayList<>();
        for (JsonNode e : n.get(f)) {
            if (e.isNumber()) out.add(e.decimalValue());
        }
        return out.isEmpty() ? null : out;
    }
}
