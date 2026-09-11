package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.PvTraceViews;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PvTraceService 单元测试（Mockito，不连 DB）。
 *
 * <p>重点：来源不混算、缺输入返回 null 而不是 0、辐照度缺失时段剔除而非当 0。
 * BigDecimal 一律用 {@code isEqualByComparingTo}（assertEquals 会因 scale 不等假失败）。
 */
@ExtendWith(MockitoExtension.class)
class PvTraceServiceTest {

    private static final Long STATION_ASSET_ID = 77L;

    @Mock
    private PvModuleRepository pvModuleRepository;
    @Mock
    private PvStationRepository pvStationRepository;
    @Mock
    private PvGenerationHourlyRepository pvGenerationHourlyRepository;
    @InjectMocks
    private PvTraceService service;

    private PvModule module(String serialNo, String batchNo, String inverterDeviceNo) {
        return PvModule.builder()
                .serialNo(serialNo).batchNo(batchNo).stationAssetId(STATION_ASSET_ID)
                .pmaxW(new BigDecimal("550.00"))
                .degradationRate(new BigDecimal("0.0050"))
                .installedAt(LocalDate.of(2024, 1, 1))
                .stringId("S1").position(3).inverterDeviceNo(inverterDeviceNo)
                .build();
    }

    @Test
    void moduleTrace_returns_profile_inverter_energy_and_degradation() {
        when(pvModuleRepository.findBySerialNo("MOD-1")).thenReturn(Optional.of(module("MOD-1", "B1", "INV-1")));
        when(pvGenerationHourlyRepository.sumEnergyWhByDeviceNoAndSource("INV-1", "INVERTER"))
                .thenReturn(new BigDecimal("123456.5000"));

        PvTraceViews.ModuleTraceView v = service.moduleTrace("MOD-1");

        assertEquals("MOD-1", v.serialNo());
        assertEquals("S1", v.stringId());
        assertThat(v.inverterEnergyWh()).isEqualByComparingTo("123456.5000");
        // 质保年限：系统内无数据源，恒 null（不编造）
        assertNull(v.warrantyYears(), "质保年限无数据源时必须为 null");
        // 衰减 = 0.0050/年 × 已装年数 × 100，必为正且随安装时间增长
        assertThat(v.estimatedDegradationPct()).isNotNull();
        assertThat(v.estimatedDegradationPct()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void moduleTrace_without_inverter_binding_returns_null_energy() {
        when(pvModuleRepository.findBySerialNo("MOD-2")).thenReturn(Optional.of(module("MOD-2", "B1", null)));

        PvTraceViews.ModuleTraceView v = service.moduleTrace("MOD-2");

        assertNull(v.inverterEnergyWh(), "未绑定逆变器时电量应为 null 而不是 0");
        verify(pvGenerationHourlyRepository, never()).sumEnergyWhByDeviceNoAndSource(any(), any());
    }

    @Test
    void moduleTrace_unknown_serial_rejected() {
        when(pvModuleRepository.findBySerialNo("NOPE")).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> service.moduleTrace("NOPE"));
        assertEquals("error.pv.module.not.found", ex.getMessageCode());
    }

    @Test
    void batchOverview_lists_modules_and_leaves_pr_null() {
        when(pvModuleRepository.findByBatchNoOrderBySerialNoAsc("B1"))
                .thenReturn(List.of(module("MOD-1", "B1", "INV-1"), module("MOD-2", "B1", "INV-1")));
        when(pvGenerationHourlyRepository.sumEnergyWhGroupByDeviceNo(any(), eq("INVERTER")))
                .thenReturn(List.<Object[]>of(new Object[]{"INV-1", new BigDecimal("900.0000")}));

        PvTraceViews.BatchOverviewView v = service.batchOverview("B1");

        assertEquals(2, v.moduleCount());
        assertNull(v.pr(), "批次级 PR 无统一装机容量分母，必须为 null 而不是编造");
        assertThat(v.modules().get(0).inverterEnergyWh()).isEqualByComparingTo("900.0000");
    }

    @Test
    void stationYield_keeps_sources_separated_and_computes_pr() {
        when(pvStationRepository.findByAssetId(STATION_ASSET_ID)).thenReturn(Optional.of(
                PvStation.builder().assetId(STATION_ASSET_ID).ratedPowerWp(new BigDecimal("100000.00")).build()));
        Instant day1 = LocalDate.of(2026, 9, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        when(pvGenerationHourlyRepository.findByStationAssetIdAndBucketAtBetweenOrderByBucketAtDesc(
                eq(STATION_ASSET_ID), any(), any()))
                .thenReturn(List.of(
                        hourly("INVERTER", day1, "5000.0000", "800.00"),   // 辐照度有效
                        hourly("INVERTER", day1, "3000.0000", null),       // 辐照度缺失 → GAP，剔除出 PR
                        hourly("METER", day1, "7000.0000", "800.00")));

        PvTraceViews.StationYieldView v = service.stationYield(STATION_ASSET_ID,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

        assertThat(v.ratedPowerWp()).isEqualByComparingTo("100000.00");
        PvTraceViews.YieldBucket day = v.daily().get(0);
        assertThat(day.inverterWh()).isEqualByComparingTo("8000.0000");   // 5000 + 3000
        assertThat(day.meterWh()).isEqualByComparingTo("7000.0000");      // 与逆变器电量分列，不合并
        assertThat(day.peakSunHours()).isEqualByComparingTo("0.8");       // 800 ÷ 1000
        // PR 分子只取辐照度有效小时（5000），分母 = 100000 × 0.8 = 80000 → 0.0625
        assertThat(day.pr()).isEqualByComparingTo("0.0625");
        assertThat(day.irradianceGap()).isTrue();
        assertEquals(1, v.monthly().size());
        assertEquals("2026-09", v.monthly().get(0).period());
    }

    @Test
    void stationYield_without_nameplate_capacity_returns_null_pr() {
        when(pvStationRepository.findByAssetId(STATION_ASSET_ID)).thenReturn(Optional.of(
                PvStation.builder().assetId(STATION_ASSET_ID).build()));   // 无 rated_power_wp
        Instant day1 = LocalDate.of(2026, 9, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        when(pvGenerationHourlyRepository.findByStationAssetIdAndBucketAtBetweenOrderByBucketAtDesc(
                eq(STATION_ASSET_ID), any(), any()))
                .thenReturn(List.of(hourly("INVERTER", day1, "5000.0000", "800.00")));

        PvTraceViews.StationYieldView v = service.stationYield(STATION_ASSET_ID,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

        assertNull(v.ratedPowerWp());
        assertNull(v.daily().get(0).pr(), "无铭牌容量时 PR 必须为 null 而不是 0");
        assertThat(v.daily().get(0).inverterWh()).isEqualByComparingTo("5000.0000");
    }

    @Test
    void stationYield_rejects_invalid_range() {
        assertThrows(BizException.class, () -> service.stationYield(
                STATION_ASSET_ID, LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1)));
    }

    @Test
    void pure_functions_return_null_when_input_missing() {
        assertNull(PvTraceService.performanceRatio(null, new BigDecimal("1000"), new BigDecimal("1")));
        assertNull(PvTraceService.performanceRatio(new BigDecimal("100"), null, new BigDecimal("1")));
        assertNull(PvTraceService.performanceRatio(new BigDecimal("100"), new BigDecimal("1000"), BigDecimal.ZERO),
                "分母为 0 时不能返回 0 充数");
        assertThat(PvTraceService.peakSunHours(new BigDecimal("2500"))).isEqualByComparingTo("2.5");
        assertNull(PvTraceService.estimateDegradationPct(null, new BigDecimal("0.005"), LocalDate.now()));
        assertNull(PvTraceService.estimateDegradationPct(LocalDate.of(2024, 1, 1), null, LocalDate.now()));
        // 2024-01-01 → 2026-01-01 共 731 天 ≈ 2.0014 年 × 0.5%/年 = 1.0007%
        assertThat(PvTraceService.estimateDegradationPct(
                LocalDate.of(2024, 1, 1), new BigDecimal("0.005"), LocalDate.of(2026, 1, 1)))
                .isEqualByComparingTo("1.0007");
    }

    private PvGenerationHourly hourly(String source, Instant bucketAt, String energyWh, String irradiance) {
        return PvGenerationHourly.builder()
                .stationAssetId(STATION_ASSET_ID).deviceNo("DEV-1").source(source).bucketAt(bucketAt)
                .energyWh(new BigDecimal(energyWh))
                .irradianceAvg(irradiance == null ? null : new BigDecimal(irradiance))
                .build();
    }
}
