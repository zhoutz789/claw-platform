package com.claw.server.domain.iot;

import com.claw.server.common.dto.LocationDtos.ProductLocationView;
import com.claw.server.common.dto.LocationDtos.TrackPointView;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocationViewServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private TelemetryLatestRepository telemetryLatestRepository;

    @Mock
    private TrackRepository trackRepository;

    @InjectMocks
    private LocationViewService service;

    @Test
    void getProductLocation_returnsLatestReportedAsset() {
        Asset a1 = Asset.builder().id(1L).assetNo("A1").productId(1L).build();
        Asset a2 = Asset.builder().id(2L).assetNo("A2").productId(1L).build();
        when(assetRepository.findByProductId(1L)).thenReturn(List.of(a1, a2));

        Instant t1 = Instant.parse("2024-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2024-01-01T12:00:00Z");
        TelemetryLatest older = TelemetryLatest.builder().id(11L).assetId(1L)
                .lat(BigDecimal.valueOf(10)).lng(BigDecimal.valueOf(20)).reportedAt(t1).build();
        TelemetryLatest newer = TelemetryLatest.builder().id(12L).assetId(2L)
                .lat(BigDecimal.valueOf(30)).lng(BigDecimal.valueOf(40)).reportedAt(t2).build();
        when(telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(1L)).thenReturn(Optional.of(older));
        when(telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(2L)).thenReturn(Optional.of(newer));

        ProductLocationView view = service.getProductLocation(1L);

        assertEquals(2L, view.assetId());
        assertEquals("A2", view.assetNo());
        assertEquals(0, BigDecimal.valueOf(30).compareTo(view.lat()));
        assertEquals(0, BigDecimal.valueOf(40).compareTo(view.lng()));
        assertEquals(t2, view.reportedAt());
    }

    @Test
    void getProductLocation_noTelemetry_returnsNullView() {
        Asset a1 = Asset.builder().id(1L).assetNo("A1").productId(1L).build();
        when(assetRepository.findByProductId(1L)).thenReturn(List.of(a1));
        when(telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(1L)).thenReturn(Optional.empty());

        ProductLocationView view = service.getProductLocation(1L);

        assertNull(view.assetId());
        assertNull(view.lat());
        assertNull(view.lng());
    }

    @Test
    void getTrack_returnsMappedPoints() {
        Track t1 = Track.builder().id(1L).assetId(5L)
                .ts(Instant.parse("2024-01-01T10:00:00Z"))
                .lat(BigDecimal.valueOf(1)).lng(BigDecimal.valueOf(2))
                .speed(BigDecimal.valueOf(30)).soc(BigDecimal.valueOf(80)).build();
        Track t2 = Track.builder().id(2L).assetId(5L)
                .ts(Instant.parse("2024-01-01T10:05:00Z"))
                .lat(BigDecimal.valueOf(3)).lng(BigDecimal.valueOf(4))
                .speed(BigDecimal.valueOf(35)).soc(BigDecimal.valueOf(75)).build();
        Instant from = Instant.parse("2024-01-01T09:00:00Z");
        Instant to = Instant.parse("2024-01-01T11:00:00Z");
        when(trackRepository.findByAssetIdAndTsBetweenOrderByTsAsc(5L, from, to))
                .thenReturn(List.of(t1, t2));

        List<TrackPointView> points = service.getTrack(5L, from, to);

        assertEquals(2, points.size());
        assertEquals(t1.getTs(), points.get(0).ts());
        assertEquals(0, BigDecimal.valueOf(1).compareTo(points.get(0).lat()));
        assertEquals(0, BigDecimal.valueOf(30).compareTo(points.get(0).speed()));
        assertEquals(0, BigDecimal.valueOf(80).compareTo(points.get(0).soc()));
        assertEquals(t2.getTs(), points.get(1).ts());
    }
}
