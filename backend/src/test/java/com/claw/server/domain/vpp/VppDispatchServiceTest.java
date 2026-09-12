package com.claw.server.domain.vpp;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceCommandService;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.TelemetryLatestRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VppDispatchService 单元测试。
 *
 * <p>纯函数部分（{@code dispatchPlan}）不连 DB、不加载 Spring；落库/下发部分用 Mockito。
 * BigDecimal 一律用 {@code isEqualByComparingTo}（assertEquals 会因 scale 不等假失败）。
 */
@ExtendWith(MockitoExtension.class)
class VppDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final BigDecimal RATED = new BigDecimal("5000");

    @Mock
    private VppResourceRepository vppResourceRepository;
    @Mock
    private VppPortfolioRepository vppPortfolioRepository;
    @Mock
    private VppDispatchOrderRepository vppDispatchOrderRepository;
    @Mock
    private TelemetryLatestRepository telemetryLatestRepository;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private DeviceCommandService deviceCommandService;
    @Mock
    private SystemConfigRepository systemConfigRepository;

    @InjectMocks
    private VppDispatchService service;

    // ===================== 快照构造 =====================

    private VppDispatchService.ResourceSnapshot pv(Long id, BigDecimal irradiance, BigDecimal acPowerW,
                                                   BigDecimal deratePercent) {
        return new VppDispatchService.ResourceSnapshot(id, VppDispatchService.PV,
                new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("10000"),
                acPowerW, deratePercent, irradiance, null, null, null, null, null, true);
    }

    private VppDispatchService.ResourceSnapshot ess(Long id, BigDecimal soc, BigDecimal tempMin,
                                                    BigDecimal ccl, BigDecimal dcl, BigDecimal packVoltage,
                                                    boolean telemetryAvailable) {
        return new VppDispatchService.ResourceSnapshot(id, VppDispatchService.ESS,
                RATED, BigDecimal.ZERO, new BigDecimal("5000"), null, null, null,
                soc, tempMin, ccl, dcl, packVoltage, telemetryAvailable);
    }

    private VppDispatchService.ResourceSnapshot charger(Long id, BigDecimal currentW) {
        return new VppDispatchService.ResourceSnapshot(id, VppDispatchService.CHARGER,
                new BigDecimal("7000"), BigDecimal.ZERO, new BigDecimal("7000"),
                currentW, null, null, null, null, null, null, null, true);
    }

    private List<VppDispatchService.CommandDraft> byType(VppDispatchService.DispatchOutcome o, String type) {
        return o.commands().stream().filter(c -> type.equals(c.commandType())).toList();
    }

    // ===================== 纯函数用例 =====================

    @Test
    void night_pv_with_zero_irradiance_produces_no_pv_command() {
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                        charger(2L, new BigDecimal("1000"))),
                NOW);

        assertThat(out.pvAvailableW()).isEqualByComparingTo("0");
        assertThat(byType(out, VppDispatchService.CMD_DERATE_PV)).isEmpty();
        // 夜间无光伏 → 负荷由储能/市电承担；本例无储能，缺口落到市电
        assertThat(out.deficitW()).isEqualByComparingTo("1000");
    }

    @Test
    void cold_ess_must_not_charge() {
        // 光伏大发 8000W，本地负荷 1000W → 余电 7000W；但储能电芯 2℃ < 5℃ 禁充
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, new BigDecimal("800"), new BigDecimal("8000"), BigDecimal.ZERO),
                        ess(2L, new BigDecimal("50"), new BigDecimal("2"),
                                new BigDecimal("30"), new BigDecimal("60"), new BigDecimal("50"), true),
                        charger(3L, new BigDecimal("1000"))),
                NOW);

        assertThat(byType(out, VppDispatchService.CMD_CHARGE_ESS)).isEmpty();
        assertThat(out.notes()).anyMatch(n -> n.contains("禁止充电"));
    }

    @Test
    void ess_discharge_is_clamped_by_bms_dcl_via_power_limit() {
        // 组电压 50V × DCL 60A = 3000W，额定 5000W 不构成上限
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                        ess(2L, new BigDecimal("80"), new BigDecimal("25"),
                                new BigDecimal("30"), new BigDecimal("60"), new BigDecimal("50"), true),
                        charger(3L, new BigDecimal("10000"))),
                NOW);

        var discharge = byType(out, VppDispatchService.CMD_DISCHARGE_ESS);
        assertThat(discharge).hasSize(1);
        assertThat(discharge.get(0).targetW()).isEqualByComparingTo("3000");
        // 直接验证钳制入口：min(策略 5000, 50×60)
        assertThat(VppDispatchService.essDischargeW(
                ess(2L, new BigDecimal("80"), new BigDecimal("25"),
                        new BigDecimal("30"), new BigDecimal("60"), new BigDecimal("50"), true),
                VppDispatchService.VppDispatchOptions.defaults())).isEqualByComparingTo("3000");
    }

    @Test
    void pv_shortfall_degrades_to_ess_discharge() {
        // 光伏 2000W < 负荷 5000W → 缺口 3000W 由储能放电（DCL 100A × 50V = 5000W 足够）
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, new BigDecimal("300"), new BigDecimal("2000"), BigDecimal.ZERO),
                        ess(2L, new BigDecimal("80"), new BigDecimal("25"),
                                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("50"), true),
                        charger(3L, new BigDecimal("5000"))),
                NOW);

        assertThat(out.deficitW()).isEqualByComparingTo("3000");
        var discharge = byType(out, VppDispatchService.CMD_DISCHARGE_ESS);
        assertThat(discharge).hasSize(1);
        assertThat(discharge.get(0).targetW()).isEqualByComparingTo("3000");
    }

    @Test
    void empty_resource_list_returns_empty_plan_without_error() {
        var out = VppDispatchService.dispatchPlan(List.of(), NOW);
        assertThat(out.commands()).isEmpty();
        assertThat(out.pvAvailableW()).isEqualByComparingTo("0");
        assertThat(out.localLoadW()).isEqualByComparingTo("0");

        var nullOut = VppDispatchService.dispatchPlan(null, NOW);
        assertThat(nullOut.commands()).isEmpty();
    }

    @Test
    void ess_without_telemetry_is_unavailable_and_not_counted() {
        var blind = ess(9L, new BigDecimal("80"), new BigDecimal("25"),
                new BigDecimal("30"), new BigDecimal("60"), new BigDecimal("50"), false);
        assertThat(VppDispatchService.essTelemetryUsable(blind)).isFalse();
        assertThat(VppDispatchService.essChargeW(blind, VppDispatchService.VppDispatchOptions.defaults()))
                .isEqualByComparingTo("0");
        assertThat(VppDispatchService.essDischargeW(blind, VppDispatchService.VppDispatchOptions.defaults()))
                .isEqualByComparingTo("0");

        // 即便额定 5000W 也不得凭空给出容量：放电指令不产生
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), blind,
                        charger(3L, new BigDecimal("4000"))),
                NOW);
        assertThat(byType(out, VppDispatchService.CMD_DISCHARGE_ESS)).isEmpty();
        assertThat(out.notes()).anyMatch(n -> n.contains("缺少 BMS 遥测"));
    }

    @Test
    void null_adjustable_min_and_max_are_tolerated() {
        var loose = new VppDispatchService.ResourceSnapshot(1L, VppDispatchService.ESS,
                null, null, null, null, null, null,
                new BigDecimal("80"), new BigDecimal("25"),
                new BigDecimal("30"), new BigDecimal("60"), new BigDecimal("50"), true);

        // adjustableMaxW / ratedPowerW 均缺失 → 上限基准 0，不应抛 NPE
        assertThat(VppDispatchService.essDischargeW(loose, VppDispatchService.VppDispatchOptions.defaults()))
                .isEqualByComparingTo("0");
        assertThat(VppDispatchService.essChargeW(loose, VppDispatchService.VppDispatchOptions.defaults()))
                .isEqualByComparingTo("0");

        var out = VppDispatchService.dispatchPlan(
                List.of(loose, charger(2L, new BigDecimal("1000")),
                        pv(3L, new BigDecimal("500"), null, null)),
                NOW);
        assertThat(out).isNotNull();
        assertThat(byType(out, VppDispatchService.CMD_DISCHARGE_ESS)).isEmpty();
    }

    @Test
    void surplus_after_ess_charge_is_curtailed_when_export_disallowed() {
        // 光伏 10000W，负荷 1000W → 余 9000W；储能只能吃 1500W（50V×CCL 30A）
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, new BigDecimal("900"), new BigDecimal("10000"), BigDecimal.ZERO),
                        ess(2L, new BigDecimal("50"), new BigDecimal("25"),
                                new BigDecimal("30"), new BigDecimal("60"), new BigDecimal("50"), true),
                        charger(3L, new BigDecimal("1000"))),
                NOW);

        var derate = byType(out, VppDispatchService.CMD_DERATE_PV);
        assertThat(derate).hasSize(1);
        // 无 TOU 电价信号 → 不上网：余电先充储能 1500W（50V×CCL 30A），
        // 再由充电桩以 SET_CHARGER_POWER 绝对功率吸收 6000W（上限 7000 − 当前 1000），仍余 1500W 转限发。
        assertThat(derate.get(0).targetW()).isEqualByComparingTo("8500");
        assertThat(out.curtailedPvW()).isEqualByComparingTo("1500");
        assertThat(out.notes()).anyMatch(n -> n.contains("限发"));
        var setCharger = byType(out, VppDispatchService.CMD_SET_CHARGER_POWER);
        assertThat(setCharger).hasSize(1);
        assertThat(setCharger.get(0).targetW()).isEqualByComparingTo("7000");
    }

    @Test
    void surplus_is_curtailed_when_export_allowed_but_no_price() {
        // 第二批口径：exportAllowed=true 但无 TOU 价格信号时，回落保守策略（限发而非上网）。
        var opt = new VppDispatchService.VppDispatchOptions(new BigDecimal("5"), new BigDecimal("10"), true);
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, new BigDecimal("900"), new BigDecimal("10000"), BigDecimal.ZERO),
                        ess(2L, new BigDecimal("50"), new BigDecimal("25"),
                                new BigDecimal("30"), new BigDecimal("60"), new BigDecimal("50"), true),
                        charger(3L, new BigDecimal("1000"))),
                NOW, opt);

        var derate = byType(out, VppDispatchService.CMD_DERATE_PV);
        assertThat(derate).hasSize(1);
        assertThat(derate.get(0).targetW()).isEqualByComparingTo("8500");
        assertThat(out.curtailedPvW()).isEqualByComparingTo("1500");
        assertThat(out.notes()).anyMatch(n -> n.contains("回落保守"));
    }

    @Test
    void surplus_is_exported_when_export_allowed_and_peak_price() {
        // 高价时段（energyPrice ≥ referencePrice）：即便 exportAllowed 由价格信号驱动，余电上网不限制。
        var opt = new VppDispatchService.VppDispatchOptions(new BigDecimal("5"), new BigDecimal("10"), true);
        var ctx = new VppDispatchService.DispatchContext("PEAK",
                new BigDecimal("0.30"), null, new BigDecimal("0.15"), null, null);
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, new BigDecimal("900"), new BigDecimal("10000"), BigDecimal.ZERO),
                        ess(2L, new BigDecimal("50"), new BigDecimal("25"),
                                new BigDecimal("30"), new BigDecimal("60"), new BigDecimal("50"), true),
                        charger(3L, new BigDecimal("1000"))),
                NOW, opt, ctx);

        var derate = byType(out, VppDispatchService.CMD_DERATE_PV);
        assertThat(derate.get(0).targetW()).isEqualByComparingTo("10000");
        assertThat(out.curtailedPvW()).isEqualByComparingTo("0");
        assertThat(out.notes()).anyMatch(n -> n.contains("上网"));
    }

    @Test
    void deficit_prefers_ess_when_demand_exceeded() {
        // 并网点需量 20000W 超目标 10000W → 优先储能放电削峰。
        var ctx = new VppDispatchService.DispatchContext("FLAT",
                null, new BigDecimal("5"), new BigDecimal("0.15"),
                new BigDecimal("20000"), new BigDecimal("10000"));
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, new BigDecimal("300"), new BigDecimal("2000"), BigDecimal.ZERO),
                        ess(2L, new BigDecimal("80"), new BigDecimal("25"),
                                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("50"), true),
                        charger(3L, new BigDecimal("5000"))),
                NOW, VppDispatchService.VppDispatchOptions.defaults(), ctx);

        assertThat(out.deficitW()).isEqualByComparingTo("3000");
        var discharge = byType(out, VppDispatchService.CMD_DISCHARGE_ESS);
        assertThat(discharge).hasSize(1);
        assertThat(discharge.get(0).targetW()).isEqualByComparingTo("3000");
        assertThat(out.notes()).anyMatch(n -> n.contains("需量") && n.contains("削峰"));
    }

    @Test
    void deficit_prefers_ess_when_peak_price() {
        // 高价时段（energyPrice ≥ referencePrice）→ 优先储能放电替代高价市电。
        var ctx = new VppDispatchService.DispatchContext("PEAK",
                new BigDecimal("0.30"), null, new BigDecimal("0.15"), null, null);
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, new BigDecimal("300"), new BigDecimal("2000"), BigDecimal.ZERO),
                        ess(2L, new BigDecimal("80"), new BigDecimal("25"),
                                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("50"), true),
                        charger(3L, new BigDecimal("5000"))),
                NOW, VppDispatchService.VppDispatchOptions.defaults(), ctx);

        assertThat(byType(out, VppDispatchService.CMD_DISCHARGE_ESS)).hasSize(1);
        assertThat(out.notes()).anyMatch(n -> n.contains("高价") && n.contains("替代高价市电"));
    }

    @Test
    void deficit_uses_grid_when_low_price_and_no_demand() {
        // 低价时段且未超需量 → 缺口由市电承担，储能保留至峰段（不产生放电指令）。
        var ctx = new VppDispatchService.DispatchContext("VALLEY",
                new BigDecimal("0.08"), null, new BigDecimal("0.15"), null, null);
        var out = VppDispatchService.dispatchPlan(
                List.of(pv(1L, new BigDecimal("300"), new BigDecimal("2000"), BigDecimal.ZERO),
                        ess(2L, new BigDecimal("80"), new BigDecimal("25"),
                                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("50"), true),
                        charger(3L, new BigDecimal("5000"))),
                NOW, VppDispatchService.VppDispatchOptions.defaults(), ctx);

        assertThat(byType(out, VppDispatchService.CMD_DISCHARGE_ESS)).isEmpty();
        assertThat(out.notes()).anyMatch(n -> n.contains("市电") && n.contains("储能保留至峰段"));
    }

    // ===================== 落库 / 影子模式 =====================

    @Test
    void shadow_mode_orders_are_flagged_and_never_reach_the_downlink() {
        when(vppPortfolioRepository.findById(1L))
                .thenReturn(Optional.of(VppPortfolio.builder().id(1L).name("站点 A").build()));
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(VppDispatchService.KEY_SHADOW_MODE))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey(VppDispatchService.KEY_SHADOW_MODE).configValue("true").build()));
        when(vppResourceRepository.findById(10L))
                .thenReturn(Optional.of(VppResource.builder().id(10L).portfolioId(1L)
                        .assetId(100L).resourceType(VppDispatchService.ESS).build()));
        when(vppDispatchOrderRepository.save(any(VppDispatchOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<VppDispatchOrder> orders = service.issueOrders(1L,
                List.of(new VppDispatchService.CommandTarget(10L, VppDispatchService.CMD_DISCHARGE_ESS,
                        new BigDecimal("3000"), NOW, NOW.plusSeconds(600), "测试")), 7L);

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getShadow()).isTrue();
        assertThat(orders.get(0).getStatus()).isEqualTo("ISSUED");
        // 安全底线：影子模式下任何下发通道都不得被调用
        verify(deviceCommandService, never()).issue(anyString(), anyString(), any());
        verify(deviceCommandService, never()).issue(anyString(), any());
    }

    @Test
    void non_shadow_mode_delivers_to_device_channel() {
        when(vppPortfolioRepository.findById(1L))
                .thenReturn(Optional.of(VppPortfolio.builder().id(1L).name("站点 A").build()));
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse(VppDispatchService.KEY_SHADOW_MODE))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey(VppDispatchService.KEY_SHADOW_MODE).configValue("false").build()));
        when(vppResourceRepository.findById(10L))
                .thenReturn(Optional.of(VppResource.builder().id(10L).portfolioId(1L)
                        .assetId(100L).resourceType(VppDispatchService.ESS).build()));
        when(vppDispatchOrderRepository.save(any(VppDispatchOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(deviceRepository.findByAssetId(100L))
                .thenReturn(List.of(Device.builder().deviceNo("BMS-1").build()));

        List<VppDispatchOrder> orders = service.issueOrders(1L,
                List.of(new VppDispatchService.CommandTarget(10L, VppDispatchService.CMD_DISCHARGE_ESS,
                        new BigDecimal("3000"), NOW, NOW.plusSeconds(600), "测试")), 7L);

        assertThat(orders.get(0).getShadow()).isFalse();
        verify(deviceCommandService).issue(anyString(), anyString(), any());
    }

    @Test
    void issue_orders_rejects_unknown_command_type() {
        when(vppPortfolioRepository.findById(1L))
                .thenReturn(Optional.of(VppPortfolio.builder().id(1L).name("站点 A").build()));

        assertThatThrownBy(() -> service.issueOrders(1L,
                List.of(new VppDispatchService.CommandTarget(10L, "REVERSE_POLARITY",
                        BigDecimal.ZERO, null, null, null)), 7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("error.vpp.command.type.invalid");
    }
}
