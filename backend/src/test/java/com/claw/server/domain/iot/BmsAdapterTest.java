package com.claw.server.domain.iot;

import com.claw.server.common.dto.BmsTelemetryReport;
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
 * BmsAdapter 归一化单元测试（纯逻辑，不加载 Spring / 不连 DB）。
 * 验证：字段映射、SOC 0–255→0–100 守卫、数组解析、物理协议在后端 Phase A 显式拒绝。
 */
class BmsAdapterTest {

    private final BmsAdapter adapter = new BmsAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void genericMqtt_maps_canonical_fields() throws Exception {
        String json = """
                {
                  "deviceNo": "BMS-001",
                  "soc": 87.5,
                  "soh": 96.0,
                  "packVoltage": 53.2,
                  "currentA": -12.4,
                  "ccl": 30.0,
                  "dcl": 60.0,
                  "temperatures": [25.1, 26.3, 24.8],
                  "cellVoltages": [3.31, 3.30, 3.32, 3.29],
                  "bmsState": "charging",
                  "chemistry": "LFP"
                }
                """;
        JsonNode node = mapper.readTree(json);
        BmsTelemetryReport r = adapter.normalize(node, BmsAdapter.Profile.GENERIC_MQTT);

        assertEquals("BMS-001", r.deviceNo());
        assertEquals(new BigDecimal("87.5"), r.soc());
        assertEquals(new BigDecimal("96.0"), r.soh());
        assertEquals(new BigDecimal("53.2"), r.packVoltage());
        assertEquals(new BigDecimal("-12.4"), r.currentA());
        assertEquals(new BigDecimal("30.0"), r.ccl());
        assertEquals(new BigDecimal("60.0"), r.dcl());
        assertEquals("charging", r.bmsState());
        assertEquals("LFP", r.chemistry());
        assertEquals(List.of(new BigDecimal("25.1"), new BigDecimal("26.3"), new BigDecimal("24.8")), r.temperatures());
        assertEquals(List.of(new BigDecimal("3.31"), new BigDecimal("3.3"),
                new BigDecimal("3.32"), new BigDecimal("3.29")), r.cellVoltages());
    }

    @Test
    void genericMqtt_guards_soc_0_255_scale() throws Exception {
        // 上游以 0–255 上报 SOC=200 → 归一到 200/255*100 ≈ 78.4314%
        String json = "{\"deviceNo\":\"BMS-002\",\"soc\":200}";
        BmsTelemetryReport r = adapter.normalize(mapper.readTree(json), BmsAdapter.Profile.GENERIC_MQTT);
        BigDecimal expected = new BigDecimal("200")
                .divide(BigDecimal.valueOf(255), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        assertEquals(0, r.soc().compareTo(expected));
        assertTrue(r.soc().compareTo(new BigDecimal("78.4")) > 0
                && r.soc().compareTo(new BigDecimal("78.5")) < 0);
    }

    @Test
    void genericMqtt_leaves_missing_fields_null() throws Exception {
        String json = "{\"deviceNo\":\"BMS-003\"}";
        BmsTelemetryReport r = adapter.normalize(mapper.readTree(json), BmsAdapter.Profile.GENERIC_MQTT);
        assertNull(r.soc());
        assertNull(r.packVoltage());
        assertNull(r.temperatures());
    }

    @Test
    void physical_profiles_rejected_in_phase_a() {
        JsonNode node = mapper.createObjectNode();
        for (BmsAdapter.Profile p : new BmsAdapter.Profile[]{
                BmsAdapter.Profile.PYLON_CAN, BmsAdapter.Profile.PYLON_MODBUS,
                BmsAdapter.Profile.GBT27930, BmsAdapter.Profile.GENERIC_MODBUS}) {
            UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                    () -> adapter.normalize(node, p));
            assertTrue(ex.getMessage().contains("先后端"), "物理协议应说明改由固件侧归一化: " + p);
        }
    }
}
