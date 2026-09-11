package com.claw.server.domain.iot;

import com.claw.server.common.dto.PvTelemetryReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PvAdapter 归一化单元测试（纯逻辑，不加载 Spring / 不连 DB）。
 * 验证：kW→W 单位归一、电表双向功率符号约定、逆变器功率恒正、厂家 profile 后置、缺失字段容错。
 *
 * <p>BigDecimal 一律用 compareTo 比较（assertEquals 会因 scale 不等而假失败）。
 */
class PvAdapterTest {

    private final PvAdapter adapter = new PvAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void genericMqtt_normalizes_kw_to_w_and_kwh_to_wh() throws Exception {
        String json = """
                {
                  "deviceNo": "PV-INV-001",
                  "timestamp": "2026-09-14T08:15:00Z",
                  "inverterState": "run",
                  "acActivePowerKw": 49.8,
                  "totalYieldKwh": 12345.678,
                  "dcPowerKw1": 25.4,
                  "demandKw": 50.0
                }
                """;
        PvTelemetryReport r = adapter.normalize(mapper.readTree(json), PvAdapter.Profile.GENERIC_MQTT);

        assertEquals("PV-INV-001", r.deviceNo());
        assertEquals("run", r.inverterState());
        assertEquals(0, r.acActivePowerW().compareTo(new BigDecimal("49800")));
        assertEquals(0, r.totalYieldWh().compareTo(new BigDecimal("12345678")));
        assertEquals(0, r.dcPowerW1().compareTo(new BigDecimal("25400")));
        assertEquals(0, r.demandW().compareTo(new BigDecimal("50000")));
    }

    @Test
    void genericMqtt_keeps_meter_sign_and_forces_inverter_power_positive() throws Exception {
        // 电表：下网（购电）为负、上网（馈网）为正 —— 双向符号必须原样保留
        PvTelemetryReport meter = adapter.normalize(mapper.readTree(
                "{\"deviceNo\":\"PV-METER-1\",\"meterActivePowerKw\":-1.2,\"forwardTotalWh\":880}"),
                PvAdapter.Profile.GENERIC_MQTT);
        assertEquals(0, meter.meterActivePowerW().compareTo(new BigDecimal("-1200")),
                "下网（购电）必须为负");
        assertEquals(0, meter.forwardTotalWh().compareTo(new BigDecimal("880")));

        // 逆变器：只发电，负号无物理意义 → 恒正
        PvTelemetryReport inv = adapter.normalize(mapper.readTree(
                "{\"deviceNo\":\"PV-INV-1\",\"acActivePowerW\":-500}"),
                PvAdapter.Profile.GENERIC_MQTT);
        assertEquals(0, inv.acActivePowerW().compareTo(new BigDecimal("500")),
                "逆变器有功功率必须恒正");
    }

    @Test
    void vendor_profiles_are_postponed_to_gateway_side() {
        JsonNode node = mapper.createObjectNode();
        for (PvAdapter.Profile p : new PvAdapter.Profile[]{
                PvAdapter.Profile.GROWATT_CLOUD, PvAdapter.Profile.HUAWEI_FUSIONSOLAR,
                PvAdapter.Profile.SUNSPEC_MODBUS}) {
            UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                    () -> adapter.normalize(node, p));
            assertTrue(ex.getMessage().contains("后置"),
                    "厂家协议应说明解析后置到接入网关侧: " + p);
        }
    }

    @Test
    void genericMqtt_leaves_missing_fields_null_and_parses_arrays() throws Exception {
        PvTelemetryReport empty = adapter.normalize(
                mapper.readTree("{\"deviceNo\":\"PV-INV-2\"}"), PvAdapter.Profile.GENERIC_MQTT);
        assertNull(empty.acActivePowerW());
        assertNull(empty.totalYieldWh());
        assertNull(empty.stringCurrents());
        assertNull(empty.inverterState());

        PvTelemetryReport full = adapter.normalize(
                mapper.readTree("{\"deviceNo\":\"PV-INV-3\",\"stringCurrents\":[8.1,8.2,7.9],"
                        + "\"irradiance\":912.5,\"moduleTemp\":48.2,\"acFrequency\":50.01}"),
                PvAdapter.Profile.GENERIC_MQTT);
        assertEquals(List.of(new BigDecimal("8.1"), new BigDecimal("8.2"), new BigDecimal("7.9")),
                full.stringCurrents());
        assertEquals(0, full.irradiance().compareTo(new BigDecimal("912.5")));
        assertEquals(0, full.moduleTemp().compareTo(new BigDecimal("48.2")));
        assertEquals(0, full.acFrequency().compareTo(new BigDecimal("50.01")));
    }
}
