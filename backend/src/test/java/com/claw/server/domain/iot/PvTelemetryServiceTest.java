package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.PvTelemetryReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PvTelemetryService 单元测试（Mockito，不连 DB）。
 *
 * <p>聚焦计量铁律：小时电量只由累计计数器差分得到、回退绝不为负、双来源分开不混算。
 * BigDecimal 一律用 compareTo 比较（assertEquals 会因 scale 不等而假失败）。
 */
@ExtendWith(MockitoExtension.class)
class PvTelemetryServiceTest {

    private static final String DEVICE_NO = "PV-INV-001";
    private static final Instant BUCKET = Instant.parse("2026-09-14T08:00:00Z");

    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private TelemetryLatestRepository telemetryLatestRepository;
    @Mock
    private PvGenerationHourlyRepository pvGenerationHourlyRepository;
    @InjectMocks
    private PvTelemetryService service;

    private Device pvDevice() {
        return Device.builder()
                .id(1L).deviceNo(DEVICE_NO).deviceType("PV_GATEWAY").assetId(10L).tenantId(1L).build();
    }

    private PvGenerationHourly row(String source, String energyWh, String cumulativeWh) {
        return PvGenerationHourly.builder()
                .stationAssetId(10L).deviceNo(DEVICE_NO).source(source).bucketAt(BUCKET)
                .energyWh(new BigDecimal(energyWh)).cumulativeWh(new BigDecimal(cumulativeWh))
                .build();
    }

    @Test
    void hourly_energy_comes_from_cumulative_counter_diff() {
        when(deviceRepository.findByDeviceNo(DEVICE_NO)).thenReturn(Optional.of(pvDevice()));
        when(telemetryLatestRepository.findByDeviceId(1L)).thenReturn(Optional.empty());
        when(pvGenerationHourlyRepository.findByDeviceNoAndBucketAtAndSource(DEVICE_NO, BUCKET, "INVERTER"))
                .thenReturn(Optional.of(row("INVERTER", "100", "1000.0000")));

        // 表底 1000 → 1250，本小时新增 250；已有 100 → 合计 350
        service.handleReport(report("2026-09-14T08:30:00Z", new BigDecimal("50000"),
                null, null, new BigDecimal("1250.0000"), null), null);

        ArgumentCaptor<PvGenerationHourly> captor = ArgumentCaptor.forClass(PvGenerationHourly.class);
        verify(pvGenerationHourlyRepository).save(captor.capture());
        PvGenerationHourly saved = captor.getValue();
        assertEquals("INVERTER", saved.getSource());
        assertEquals(0, saved.getEnergyWh().compareTo(new BigDecimal("350")), "100 + (1250-1000) = 350");
        assertEquals(0, saved.getCumulativeWh().compareTo(new BigDecimal("1250.0000")), "表底读数须留存作审计锚点");
        assertEquals(0, saved.getPeakPowerW().compareTo(new BigDecimal("50000")));
    }

    @Test
    void cumulative_regression_freezes_diff_and_never_yields_negative_energy() {
        when(deviceRepository.findByDeviceNo(DEVICE_NO)).thenReturn(Optional.of(pvDevice()));
        when(telemetryLatestRepository.findByDeviceId(1L)).thenReturn(Optional.empty());
        when(pvGenerationHourlyRepository.findByDeviceNoAndBucketAtAndSource(DEVICE_NO, BUCKET, "INVERTER"))
                .thenReturn(Optional.of(row("INVERTER", "100", "1000.0000")));

        // 回退：表底 1000 → 800（换表/清零/厂家 bug）
        service.handleReport(report("2026-09-14T08:45:00Z", null,
                null, null, new BigDecimal("800.0000"), null), null);

        ArgumentCaptor<PvGenerationHourly> captor = ArgumentCaptor.forClass(PvGenerationHourly.class);
        verify(pvGenerationHourlyRepository).save(captor.capture());
        PvGenerationHourly saved = captor.getValue();
        assertEquals(0, saved.getEnergyWh().compareTo(new BigDecimal("100")), "回退时本小时电量冻结不减，绝不为负");
        assertEquals(0, saved.getCumulativeWh().compareTo(new BigDecimal("800.0000")),
                "表底读数前移，避免后续差分永久卡死");
        assertTrue(saved.getEnergyWh().compareTo(BigDecimal.ZERO) >= 0);
    }

    @Test
    void missing_device_no_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.handleReport(report(null, null, null, null, null, null, null), null));
        assertEquals("error.pv.device.no.missing", ex.getMessageCode());
        verifyNoInteractions(deviceRepository, telemetryLatestRepository, pvGenerationHourlyRepository);
    }

    @Test
    void inverter_and_meter_sources_are_never_mixed() {
        when(deviceRepository.findByDeviceNo(DEVICE_NO)).thenReturn(Optional.of(pvDevice()));
        when(telemetryLatestRepository.findByDeviceId(1L)).thenReturn(Optional.empty());
        when(pvGenerationHourlyRepository.findByDeviceNoAndBucketAtAndSource(DEVICE_NO, BUCKET, "INVERTER"))
                .thenReturn(Optional.of(row("INVERTER", "200", "4900.0000")));
        when(pvGenerationHourlyRepository.findByDeviceNoAndBucketAtAndSource(DEVICE_NO, BUCKET, "METER"))
                .thenReturn(Optional.of(row("METER", "150", "2900.0000")));

        // 同一报文同时带逆变器总发电量与电表正向总电能：各来源独立差分
        service.handleReport(report("2026-09-14T08:30:00Z", new BigDecimal("40000"),
                new BigDecimal("30000"), new BigDecimal("3000.0000"),
                new BigDecimal("5000.0000"), null), null);

        ArgumentCaptor<PvGenerationHourly> captor = ArgumentCaptor.forClass(PvGenerationHourly.class);
        verify(pvGenerationHourlyRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<PvGenerationHourly> saved = captor.getAllValues();

        PvGenerationHourly inverter = saved.stream().filter(r -> "INVERTER".equals(r.getSource())).findFirst().orElseThrow();
        PvGenerationHourly meter = saved.stream().filter(r -> "METER".equals(r.getSource())).findFirst().orElseThrow();

        assertEquals(0, inverter.getEnergyWh().compareTo(new BigDecimal("300")), "200 + (5000-4900)");
        assertEquals(0, inverter.getCumulativeWh().compareTo(new BigDecimal("5000.0000")));
        assertEquals(0, inverter.getPeakPowerW().compareTo(new BigDecimal("40000")));

        assertEquals(0, meter.getEnergyWh().compareTo(new BigDecimal("250")), "150 + (3000-2900)");
        assertEquals(0, meter.getCumulativeWh().compareTo(new BigDecimal("3000.0000")));
        assertEquals(0, meter.getPeakPowerW().compareTo(new BigDecimal("30000")));
    }

    @Test
    void without_cumulative_counter_no_hourly_row_is_written() {
        when(deviceRepository.findByDeviceNo(DEVICE_NO)).thenReturn(Optional.of(pvDevice()));
        when(telemetryLatestRepository.findByDeviceId(1L)).thenReturn(Optional.empty());

        // 只有功率没有累计计数器：绝不拿功率积分补电量（宁缺勿假）
        service.handleReport(report("2026-09-14T08:30:00Z", new BigDecimal("42000"),
                null, null, null, null), null);

        verify(pvGenerationHourlyRepository, never()).save(any());
    }

    @Test
    void bucket_at_is_truncated_to_hour() {
        assertEquals(BUCKET, PvTelemetryService.bucketOf(Instant.parse("2026-09-14T08:59:59Z")));
        assertEquals(BUCKET, PvTelemetryService.bucketOf(Instant.parse("2026-09-14T08:00:00Z")));
    }

    @Test
    void diff_energy_returns_null_without_baseline_or_on_regression() {
        assertEquals(0, PvTelemetryService.diffEnergy(new BigDecimal("200"), new BigDecimal("150"))
                .compareTo(new BigDecimal("50")));
        assertNull(PvTelemetryService.diffEnergy(new BigDecimal("200"), null), "首个样本只建基线");
        assertNull(PvTelemetryService.diffEnergy(new BigDecimal("80"), new BigDecimal("100")), "回退不产生负电量");
    }

    /**
     * 构造规范报文（仅填本测试关心的字段，其余为 null）。
     *
     * @param deviceNo         设备编号（可为 null，用于校验必填）
     * @param timestamp        报文时间（ISO-8601）
     * @param acActivePowerW   逆变器交流有功 W
     * @param meterActivePowerW 电表有功 W（上网正/下网负）
     * @param forwardTotalWh   电表正向有功总电能 Wh
     * @param totalYieldWh     逆变器累计发电量 Wh
     * @param irradiance       辐照度 W/m²
     */
    private PvTelemetryReport report(String deviceNo, String timestamp, BigDecimal acActivePowerW,
                                     BigDecimal meterActivePowerW, BigDecimal forwardTotalWh,
                                     BigDecimal totalYieldWh, BigDecimal irradiance) {
        return new PvTelemetryReport(
                deviceNo, timestamp, "run",
                null, null, null,
                null, null, null,
                null, acActivePowerW, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null,
                meterActivePowerW, forwardTotalWh, null, null,
                irradiance, null, null, null, null,
                null, totalYieldWh,
                null, null, null, null, null);
    }

    /** 便捷重载：设备编号取 {@link #DEVICE_NO}。 */
    private PvTelemetryReport report(String timestamp, BigDecimal acActivePowerW,
                                     BigDecimal meterActivePowerW, BigDecimal forwardTotalWh,
                                     BigDecimal totalYieldWh, BigDecimal irradiance) {
        return report(DEVICE_NO, timestamp, acActivePowerW, meterActivePowerW,
                forwardTotalWh, totalYieldWh, irradiance);
    }
}
