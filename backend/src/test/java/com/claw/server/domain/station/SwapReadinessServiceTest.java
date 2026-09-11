package com.claw.server.domain.station;

import com.claw.server.domain.iot.TelemetryLatest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SwapReadinessService 判定单测（纯逻辑 decide，不连 DB / 不加载 Spring）。
 * 阈值：socMin=95, tempMin=5, tempMax=45, sohMin=80。
 */
class SwapReadinessServiceTest {

    private final SwapReadinessService svc = new SwapReadinessService(null, null, null, null, null);
    private static final int SOC_MIN = 95, TEMP_MIN = 5, TEMP_MAX = 45, SOH_MIN = 80;

    private TelemetryLatest t(String soc, String soh, String temp) {
        return TelemetryLatest.builder()
                .soc(bd(soc)).soh(bd(soh)).temp(bd(temp))
                .build();
    }

    private static BigDecimal bd(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    @Test
    void ready_when_all_metrics_pass() {
        var r = svc.decide(t("96", "90", "25"), SOC_MIN, TEMP_MIN, TEMP_MAX, SOH_MIN);
        assertEquals(SwapReadinessService.Decision.READY, r.decision());
        assertFalse(r.shouldTriggerHeating());
    }

    @Test
    void low_temp_triggers_heating() {
        var r = svc.decide(t("96", "90", "3"), SOC_MIN, TEMP_MIN, TEMP_MAX, SOH_MIN);
        assertEquals(SwapReadinessService.Decision.NEEDS_HEATING, r.decision());
        assertTrue(r.shouldTriggerHeating());
    }

    @Test
    void low_temp_uses_tempMin_probe_when_present() {
        var tl = t("96", "90", "25");
        tl.setTempMin(bd("2"));   // 最冷探头 2℃ → 即便标量 25℃ 也判需预热
        var r = svc.decide(tl, SOC_MIN, TEMP_MIN, TEMP_MAX, SOH_MIN);
        assertEquals(SwapReadinessService.Decision.NEEDS_HEATING, r.decision());
    }

    @Test
    void high_temp_is_fault_and_suggests_cooling() {
        var r = svc.decide(t("96", "90", "50"), SOC_MIN, TEMP_MIN, TEMP_MAX, SOH_MIN);
        assertEquals(SwapReadinessService.Decision.FAULT, r.decision());
        assertTrue(r.shouldTriggerCooling());
    }

    @Test
    void low_soh_marks_pending_recovery() {
        var r = svc.decide(t("96", "70", "25"), SOC_MIN, TEMP_MIN, TEMP_MAX, SOH_MIN);
        assertEquals(SwapReadinessService.Decision.PENDING_RECOVERY, r.decision());
    }

    @Test
    void soc_below_threshold_keeps_charging() {
        var r = svc.decide(t("80", "90", "25"), SOC_MIN, TEMP_MIN, TEMP_MAX, SOH_MIN);
        assertEquals(SwapReadinessService.Decision.CHARGING, r.decision());
    }

    @Test
    void protection_flags_cause_fault() {
        var tl = t("96", "90", "25");
        tl.setFaults("{\"protection\":{\"overVoltage\":true}}");
        assertEquals(SwapReadinessService.Decision.FAULT,
                svc.decide(tl, SOC_MIN, TEMP_MIN, TEMP_MAX, SOH_MIN).decision());
    }

    @Test
    void discharge_disabled_causes_fault() {
        var tl = t("96", "90", "25");
        tl.setDischargeEnable(Boolean.FALSE);
        assertEquals(SwapReadinessService.Decision.FAULT,
                svc.decide(tl, SOC_MIN, TEMP_MIN, TEMP_MAX, SOH_MIN).decision());
    }

    @Test
    void bms_state_protect_causes_fault() {
        var tl = t("96", "90", "25");
        tl.setBmsState("protect");
        assertEquals(SwapReadinessService.Decision.FAULT,
                svc.decide(tl, SOC_MIN, TEMP_MIN, TEMP_MAX, SOH_MIN).decision());
    }

    @Test
    void missing_telemetry_keeps_charging() {
        var r = svc.decide(null, SOC_MIN, TEMP_MIN, TEMP_MAX, SOH_MIN);
        assertEquals(SwapReadinessService.Decision.CHARGING, r.decision());
    }
}
