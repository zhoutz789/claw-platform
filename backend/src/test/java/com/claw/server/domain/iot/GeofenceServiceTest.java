package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.GeofenceDtos.CreateGeofenceReq;
import com.claw.server.common.dto.GeofenceDtos.GeofenceBreachView;
import com.claw.server.common.dto.GeofenceDtos.GeofenceView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeofenceServiceTest {

    @Mock
    private GeofenceRepository geofenceRepository;

    @InjectMocks
    private GeofenceService service;

    @Test
    void create_radiusSuccess_returnsView() {
        CreateGeofenceReq req = new CreateGeofenceReq("PRODUCT", 1L, "RADIUS",
                BigDecimal.ZERO, BigDecimal.ZERO, 100, null, "ALERT");
        when(geofenceRepository.save(any(Geofence.class))).thenAnswer(inv -> {
            Geofence g = inv.getArgument(0);
            g.setId(1L);
            return g;
        });

        GeofenceView view = service.create(req);

        assertNotNull(view);
        assertEquals("RADIUS", view.fenceType());
        assertEquals(100, view.radiusM());
        assertEquals("ENABLED", view.status());
        assertEquals("ALERT", view.triggerAction());
    }

    @Test
    void create_invalidFenceType_throws() {
        CreateGeofenceReq req = new CreateGeofenceReq("PRODUCT", 1L, "TRIANGLE",
                BigDecimal.ZERO, BigDecimal.ZERO, 100, null, "ALERT");
        assertThrows(BizException.class, () -> service.create(req));
    }

    @Test
    void create_radiusMissingCenter_throws() {
        CreateGeofenceReq req = new CreateGeofenceReq("PRODUCT", 1L, "RADIUS",
                null, BigDecimal.ZERO, 100, null, "ALERT");
        assertThrows(BizException.class, () -> service.create(req));
    }

    @Test
    void get_notFound_throws() {
        when(geofenceRepository.findById(42L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.get(42L));
    }

    @Test
    void checkBreach_radius_insideIsBreached() {
        Geofence fence = Geofence.builder().id(1L).ownerType("PRODUCT").ownerId(1L)
                .fenceType("RADIUS").centerLat(BigDecimal.ZERO).centerLng(BigDecimal.ZERO)
                .radiusM(100).status("ENABLED").build();
        when(geofenceRepository.findByOwnerTypeAndOwnerIdAndStatus(any(), anyLong(), eq("ENABLED")))
                .thenReturn(List.of(fence));

        // 约 0.0005° 纬度 ≈ 55.6m，明显在半径 100m 内 → 越界
        List<GeofenceBreachView> result = service.checkBreach("PRODUCT", 1L,
                BigDecimal.valueOf(0.0005), BigDecimal.ZERO);

        assertEquals(1, result.size());
        assertTrue(result.get(0).breached());
        assertEquals("INSIDE_RADIUS", result.get(0).reason());
        assertNotNull(result.get(0).distanceMeters());
    }

    @Test
    void checkBreach_radius_outsideIsNotBreached() {
        Geofence fence = Geofence.builder().id(1L).ownerType("PRODUCT").ownerId(1L)
                .fenceType("RADIUS").centerLat(BigDecimal.ZERO).centerLng(BigDecimal.ZERO)
                .radiusM(100).status("ENABLED").build();
        when(geofenceRepository.findByOwnerTypeAndOwnerIdAndStatus(any(), anyLong(), eq("ENABLED")))
                .thenReturn(List.of(fence));

        // 1° 纬度 ≈ 111km，远超半径 → 未越界
        List<GeofenceBreachView> result = service.checkBreach("PRODUCT", 1L,
                BigDecimal.valueOf(1.0), BigDecimal.ZERO);

        assertEquals(1, result.size());
        assertFalse(result.get(0).breached());
        assertEquals("OUTSIDE_RADIUS", result.get(0).reason());
    }

    @Test
    void checkBreach_polygon_insideAndOutside() {
        Geofence fence = Geofence.builder().id(2L).ownerType("DEVICE").ownerId(9L)
                .fenceType("POLYGON")
                .polygonWkt("POLYGON((0 0, 0 1, 1 1, 1 0, 0 0))")
                .status("ENABLED").build();
        when(geofenceRepository.findByOwnerTypeAndOwnerIdAndStatus(any(), anyLong(), eq("ENABLED")))
                .thenReturn(List.of(fence));

        List<GeofenceBreachView> inside = service.checkBreach("DEVICE", 9L,
                BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.5));
        assertTrue(inside.get(0).breached());
        assertEquals("INSIDE_POLYGON", inside.get(0).reason());

        List<GeofenceBreachView> outside = service.checkBreach("DEVICE", 9L,
                BigDecimal.valueOf(2.0), BigDecimal.valueOf(2.0));
        assertFalse(outside.get(0).breached());
        assertEquals("OUTSIDE_POLYGON", outside.get(0).reason());
    }

    @Test
    void checkBreach_polygonUnsupportedWkt_returnsGraceful() {
        Geofence fence = Geofence.builder().id(3L).ownerType("ASSET").ownerId(5L)
                .fenceType("POLYGON").polygonWkt("NOT_A_WKT").status("ENABLED").build();
        when(geofenceRepository.findByOwnerTypeAndOwnerIdAndStatus(any(), anyLong(), eq("ENABLED")))
                .thenReturn(List.of(fence));

        List<GeofenceBreachView> result = service.checkBreach("ASSET", 5L,
                BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.5));

        assertEquals(1, result.size());
        assertFalse(result.get(0).breached());
        assertEquals("POLYGON_NOT_SUPPORTED", result.get(0).reason());
    }

    @Test
    void checkBreach_disabledFence_skipped() {
        // 该围栏状态为 DISABLED；ENABLED 查询由 DB 过滤，故仓储对 ENABLED 返回空。
        when(geofenceRepository.findByOwnerTypeAndOwnerIdAndStatus(any(), anyLong(), eq("ENABLED")))
                .thenReturn(List.of());

        List<GeofenceBreachView> result = service.checkBreach("PROJECT", 7L,
                BigDecimal.ZERO, BigDecimal.ZERO);

        // DISABLED 不纳入越界判定
        assertTrue(result.isEmpty());
    }
}
