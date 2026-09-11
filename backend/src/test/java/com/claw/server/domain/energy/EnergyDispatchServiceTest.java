package com.claw.server.domain.energy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EnergyDispatchService 调度决策单测（纯逻辑 planFor / powerLimit，不连 DB / 不加载 Spring）。
 * 策略：socMax=95, socMin=20, tempMin=5, tempMax=45, maxPowerW=3000。
 */
class EnergyDispatchServiceTest {

    private final EnergyDispatchService svc = new EnergyDispatchService(null, null, null, null);

    private static final BigDecimal SOC_MAX = new BigDecimal("95");
    private static final BigDecimal SOC_MIN = new BigDecimal("20");
    private static final BigDecimal T_MIN = new BigDecimal("5");
    private static final BigDecimal T_MAX = new BigDecimal("45");
    private static final BigDecimal MAX_P = new BigDecimal("3000");

    /** 常温健康包：组电压 50V，CCL 30A（→1500W），DCL 60A（→3000W）。 */
    private EnergyDispatchService.BatterySnapshot snap(BigDecimal soc) {
        return new EnergyDispatchService.BatterySnapshot(1L, "STORAGE", soc, new BigDecimal("90"),
                new BigDecimal("25"), new BigDecimal("30"), new BigDecimal("50"),
                new BigDecimal("30"), new BigDecimal("60"));
    }

    private EnergyDispatchService.DispatchPolicy policy(boolean charge, boolean discharge) {
        return new EnergyDispatchService.DispatchPolicy(SOC_MAX, SOC_MIN, T_MIN, T_MAX, charge, discharge, MAX_P);
    }

    @Test
    void cold_battery_must_heat_and_not_charge() {
        var s = new EnergyDispatchService.BatterySnapshot(1L, "STORAGE", new BigDecimal("60"),
                new BigDecimal("90"), new BigDecimal("2"), new BigDecimal("10"),
                new BigDecimal("50"), new BigDecimal("30"), new BigDecimal("60"));
        var p = svc.planFor(s, policy(true, false));
        assertEquals(EnergyDispatchService.Action.HEAT, p.action());
        assertEquals(0, p.targetPowerW().compareTo(BigDecimal.ZERO));
    }

    @Test
    void hot_battery_must_cool() {
        var s = new EnergyDispatchService.BatterySnapshot(1L, "STORAGE", new BigDecimal("60"),
                new BigDecimal("90"), new BigDecimal("48"), new BigDecimal("52"),
                new BigDecimal("50"), new BigDecimal("30"), new BigDecimal("60"));
        assertEquals(EnergyDispatchService.Action.COOL,
                svc.planFor(s, policy(true, false)).action());
    }

    @Test
    void charge_window_respects_bms_ccl() {
        var p = svc.planFor(snap(new BigDecimal("60")), policy(true, false));
        assertEquals(EnergyDispatchService.Action.CHARGE, p.action());
        // min(3000, 50V × 30A) = 1500W —— 不得超过 BMS 动态限值
        assertEquals(0, p.targetPowerW().compareTo(new BigDecimal("1500")));
    }

    @Test
    void discharge_window_respects_bms_dcl() {
        var p = svc.planFor(snap(new BigDecimal("80")), policy(false, true));
        assertEquals(EnergyDispatchService.Action.DISCHARGE, p.action());
        // min(3000, 50V × 60A=3000) = 3000W
        assertEquals(0, p.targetPowerW().compareTo(new BigDecimal("3000")));
    }

    @Test
    void soc_at_max_and_no_discharge_window_is_idle() {
        var p = svc.planFor(snap(new BigDecimal("99")), policy(true, false));
        assertEquals(EnergyDispatchService.Action.IDLE, p.action());
    }

    @Test
    void missing_soc_is_idle() {
        var p = svc.planFor(snap(null), policy(true, true));
        assertEquals(EnergyDispatchService.Action.IDLE, p.action());
    }

    @Test
    void power_limit_falls_back_to_policy_when_telemetry_missing() {
        assertEquals(0, EnergyDispatchService.powerLimit(null, new BigDecimal("30"), MAX_P)
                .compareTo(MAX_P));
        assertEquals(0, EnergyDispatchService.powerLimit(new BigDecimal("50"), null, MAX_P)
                .compareTo(MAX_P));
    }

    @Test
    void power_limit_never_exceeds_policy_cap() {
        // 组电压 100V × DCL 100A = 10000W，但策略上限 3000W → 取 3000W
        assertEquals(0, EnergyDispatchService.powerLimit(
                        new BigDecimal("100"), new BigDecimal("100"), MAX_P)
                .compareTo(MAX_P));
    }

    @Test
    void plan_dispatch_covers_whole_fleet() {
        var fleet = List.of(snap(new BigDecimal("60")), snap(new BigDecimal("80")));
        var plan = svc.planDispatch(fleet, policy(true, false));
        assertEquals(2, plan.plans().size());
    }
}
