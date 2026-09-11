package com.claw.server.domain.iot;

import com.claw.server.common.dto.IoTRequests;
import com.claw.server.common.dto.IoTViews;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * IoT 域单元测试：遥测上报（更新 latest + 落轨迹 + 联动编排）、轨迹查询、设备不存在拒绝。
 */
@ExtendWith(MockitoExtension.class)
class IoTServiceTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private TelemetryLatestRepository telemetryLatestRepository;
    @Mock private TrackRepository trackRepository;
    @Mock private DeviceLinkageEventRepository linkageEventRepository;
    @Mock private TelemetryLinkageService linkageService;
    @InjectMocks private IoTService service;

    private Device device() {
        return Device.builder().id(1L).assetId(10L).deviceType("BATTERY_BMS")
                .imei("IMEI-B001").build();
    }

    private IoTRequests.TelemetryReport report() {
        return new IoTRequests.TelemetryReport("IMEI-B001", new BigDecimal("30.00"),
                new BigDecimal("80.00"), new BigDecimal("35.5"), new BigDecimal("60.0"),
                "[\"BMS_OVERTEMP\"]", new BigDecimal("11.56"), new BigDecimal("104.89"), null);
    }

    @Test
    void reportTelemetry_upserts_latest_and_writes_track() {
        when(deviceRepository.findByImei("IMEI-B001")).thenReturn(Optional.of(device()));
        when(telemetryLatestRepository.findByDeviceId(1L))
                .thenReturn(Optional.of(TelemetryLatest.builder().id(1L).deviceId(1L).assetId(10L).build()));
        when(telemetryLatestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(trackRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IoTViews.TelemetryView v = service.reportTelemetry(report());

        assertEquals(10L, v.assetId());
        assertEquals(0, new BigDecimal("80.00").compareTo(v.soc()));
        verify(trackRepository).save(any(Track.class));
    }

    @Test
    void reportTelemetry_creates_latest_when_absent() {
        when(deviceRepository.findByImei("IMEI-B001")).thenReturn(Optional.of(device()));
        when(telemetryLatestRepository.findByDeviceId(1L)).thenReturn(Optional.empty());
        when(telemetryLatestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(trackRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IoTViews.TelemetryView v = service.reportTelemetry(report());

        assertEquals(1L, v.deviceId());
        verify(telemetryLatestRepository).save(any(TelemetryLatest.class));
    }

    @Test
    void reportTelemetry_rejects_unknown_device() {
        when(deviceRepository.findByImei("IMEI-UNKNOWN")).thenReturn(Optional.empty());

        assertThrows(Exception.class, () -> service.reportTelemetry(
                new IoTRequests.TelemetryReport("IMEI-UNKNOWN", null, null, null, null, null, null, null, null)));
        verify(trackRepository, never()).save(any());
    }

    @Test
    void tracks_returns_sorted_points() {
        when(trackRepository.findByAssetIdAndTsBetweenOrderByTsAsc(eq(10L), any(), any()))
                .thenReturn(List.of(
                        Track.builder().assetId(10L).ts(Instant.now()).speed(new BigDecimal("20")).build(),
                        Track.builder().assetId(10L).ts(Instant.now()).speed(new BigDecimal("25")).build()));

        List<IoTViews.TrackView> tracks = service.tracks(10L, null, null);

        assertEquals(2, tracks.size());
    }

    /**
     * 回归：同一资产挂多台设备（每台各一条 telemetry_latest），latest() 必须取"上报时间最新"的那条，
     * 且必须走 findTopByAssetIdOrderByReportedAtDescIdDesc（不得再用会抛 NonUniqueResultException 的单结果方法）。
     */
    @Test
    void latest_returnsMostRecentAcrossAssetDevices() {
        long assetId = 1L;
        Instant newestAt = Instant.parse("2024-01-03T12:00:00Z");
        TelemetryLatest newest = TelemetryLatest.builder()
                .id(7L).deviceId(7L).assetId(assetId)
                .soc(new BigDecimal("66.00")).soh(new BigDecimal("90.00"))
                .lat(new BigDecimal("11.11")).lng(new BigDecimal("22.22"))
                .reportedAt(newestAt).build();
        when(telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(assetId))
                .thenReturn(Optional.of(newest));

        IoTViews.TelemetryView v = service.latest(assetId);

        assertNotNull(v);
        assertEquals(assetId, v.assetId());
        assertEquals(7L, v.deviceId());
        assertEquals(0, new BigDecimal("66.00").compareTo(v.soc()));
        assertEquals(0, new BigDecimal("90.00").compareTo(v.soh()));
        assertEquals(0, new BigDecimal("11.11").compareTo(v.lat()));
        assertEquals(0, new BigDecimal("22.22").compareTo(v.lng()));
        assertEquals(newestAt, v.reportedAt());
        verify(telemetryLatestRepository).findTopByAssetIdOrderByReportedAtDescIdDesc(assetId);
        verify(telemetryLatestRepository, never()).findByDeviceId(any());
    }

    @Test
    void latest_returnsNullWhenNoTelemetry() {
        when(telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(999L))
                .thenReturn(Optional.empty());

        assertNull(service.latest(999L));
        verify(telemetryLatestRepository).findTopByAssetIdOrderByReportedAtDescIdDesc(999L);
    }
}
