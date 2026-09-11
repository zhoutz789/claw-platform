package com.claw.server.domain.iot;

import com.claw.server.common.dto.PvTelemetryReport;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 光伏协议适配层（光伏数据链路切片）。
 *
 * <p>职责：把逆变器/电表/气象站经各厂家协议上报的原始报文，归一化为
 * {@link PvTelemetryReport} 规范结构（统一单位、统一功率符号口径），再经
 * {@code claw/iot/{deviceNo}/telemetry} 上行。
 *
 * <p>单位归一（GENERIC_MQTT 边缘网关上送时仍常见 kW / kWh 口径）：
 * <ul>
 *   <li>功率：{@code xxxKw} → W（×1000）；已以 W 上送的 {@code xxxW} 直接使用。</li>
 *   <li>电量：{@code xxxKwh} → Wh（×1000）；已以 Wh 上送的 {@code xxxWh} 直接使用。</li>
 * </ul>
 *
 * <p>功率符号约定：
 * <ul>
 *   <li>逆变器 {@code acActivePowerW}：<b>恒正</b>——逆变器只发电，负号无物理意义，取绝对值。</li>
 *   <li>电表 {@code meterActivePowerW}：<b>上网为正、下网为负</b>——双向计量必须保留符号，
 *       否则"买了多少电"与"卖了多少电"会混为一谈。</li>
 * </ul>
 *
 * <p>多 profile 可插拔：新增厂家只加一个 profile 分支。当前实现：
 * <ul>
 *   <li>{@code GENERIC_MQTT}：报文已是规范 JSON（边缘网关/数采器完成 Modbus RTU/TCP → MQTT
 *       归一化），后端仅做字段映射 + 单位归一 + 符号守卫，无重解析。</li>
 *   <li>{@code GROWATT_CLOUD} / {@code HUAWEI_FUSIONSOLAR} / {@code SUNSPEC_MODBUS}：
 *       厂商云 OpenAPI 拉取与 SunSpec 寄存器解析属接入侧工作，按"先后端、后接入"决策
 *       <b>后置</b>到接入网关侧完成，后端本切片仅接收归一化后的 GENERIC_MQTT，故此处显式拒绝并说明归属。</li>
 * </ul>
 */
@Component
@Slf4j
public class PvAdapter {

    public enum Profile {
        GENERIC_MQTT,
        GROWATT_CLOUD,
        HUAWEI_FUSIONSOLAR,
        SUNSPEC_MODBUS
    }

    /** kW → W 换算系数。 */
    private static final BigDecimal KW_TO_W = BigDecimal.valueOf(1000);

    /**
     * 把原始报文按 profile 归一化为规范结构。
     *
     * @throws UnsupportedOperationException 厂家协议 profile 尚未在后端实现（后置到接入网关侧）
     */
    public PvTelemetryReport normalize(JsonNode raw, Profile profile) {
        return switch (profile) {
            case GENERIC_MQTT -> fromGenericMqtt(raw);
            case GROWATT_CLOUD, HUAWEI_FUSIONSOLAR, SUNSPEC_MODBUS ->
                    throw new UnsupportedOperationException(
                            "厂家协议 " + profile + " 的解析（厂商云 OpenAPI / SunSpec 寄存器）后置到接入网关侧完成；" +
                                    "按\"先后端、后接入\"决策，后端本切片仅接收归一化后的 GENERIC_MQTT 报文。");
        };
    }

    /** GENERIC_MQTT：报文即规范 JSON，做字段映射、单位归一与功率符号守卫。 */
    private PvTelemetryReport fromGenericMqtt(JsonNode n) {
        return new PvTelemetryReport(
                str(n, "deviceNo"),
                str(n, "timestamp"),
                str(n, "inverterState"),

                // 交流侧：功率支持 kW 口径，逆变器有功恒正
                dec(n, "acVoltageA"), dec(n, "acVoltageB"), dec(n, "acVoltageC"),
                dec(n, "acCurrentA"), dec(n, "acCurrentB"), dec(n, "acCurrentC"),
                dec(n, "acFrequency"),
                abs(watts(n, "acActivePowerW", "acActivePowerKw")),
                dec(n, "acReactivePowerVar"),
                dec(n, "powerFactor"),

                // 直流侧
                dec(n, "dcVoltage1"), dec(n, "dcVoltage2"), dec(n, "dcVoltage3"), dec(n, "dcVoltage4"),
                dec(n, "dcCurrent1"), dec(n, "dcCurrent2"), dec(n, "dcCurrent3"), dec(n, "dcCurrent4"),
                watts(n, "dcPowerW1", "dcPowerKw1"), watts(n, "dcPowerW2", "dcPowerKw2"),
                watts(n, "dcPowerW3", "dcPowerKw3"), watts(n, "dcPowerW4", "dcPowerKw4"),

                listDec(n, "stringCurrents"),

                // 电表：保留双向符号（上网正 / 下网负），仅做单位归一
                watts(n, "meterActivePowerW", "meterActivePowerKw"),
                wattHours(n, "forwardTotalWh", "forwardTotalKwh"),
                wattHours(n, "reverseTotalWh", "reverseTotalKwh"),
                watts(n, "demandW", "demandKw"),

                // 气象
                dec(n, "irradiance"), dec(n, "moduleTemp"), dec(n, "ambientTemp"),
                dec(n, "windSpeed"), dec(n, "dailyIrradiation"),

                // 发电量（累计计数器）
                wattHours(n, "dailyYieldWh", "dailyYieldKwh"),
                wattHours(n, "totalYieldWh", "totalYieldKwh"),

                // 状态
                intOf(n, "faultCode"), dec(n, "deratePercent"),
                dec(n, "internalTemp"), dec(n, "heatsinkTemp"), dec(n, "efficiency")
        );
    }

    /** 功率归一：优先取 W 字段，缺失时取 kW 字段 ×1000。 */
    private BigDecimal watts(JsonNode n, String wField, String kwField) {
        BigDecimal w = dec(n, wField);
        if (w != null) {
            return w;
        }
        BigDecimal kw = dec(n, kwField);
        return kw == null ? null : kw.multiply(KW_TO_W);
    }

    /** 电量归一：优先取 Wh 字段，缺失时取 kWh 字段 ×1000。 */
    private BigDecimal wattHours(JsonNode n, String whField, String kwhField) {
        BigDecimal wh = dec(n, whField);
        if (wh != null) {
            return wh;
        }
        BigDecimal kwh = dec(n, kwhField);
        return kwh == null ? null : kwh.multiply(KW_TO_W);
    }

    /** 逆变器功率恒正守卫：取绝对值（逆变器只发电，负号无物理意义）。 */
    private BigDecimal abs(BigDecimal v) {
        return v == null ? null : v.abs();
    }

    private String str(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private BigDecimal dec(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).decimalValue() : null;
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
