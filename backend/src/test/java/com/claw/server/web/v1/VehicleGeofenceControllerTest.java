package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.GeofenceLevel;
import com.claw.server.domain.autonomy.AutonomyGeofenceService;
import com.claw.server.domain.autonomy.GroundGeofence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link VehicleGeofenceController} 的接线测试（无 Spring 上下文，纯 Mockito）。
 * 覆盖：围栏列表、校验成功→true，以及四类越界异常按消息映射到 40970/40971/40972/40973。
 */
@ExtendWith(MockitoExtension.class)
class VehicleGeofenceControllerTest {

    @Mock
    private AutonomyGeofenceService geofenceService;

    @InjectMocks
    private VehicleGeofenceController controller;

    @Test
    void listGeofences_returnsList() {
        List<GroundGeofence> list = List.of(GroundGeofence.builder()
                .name("G").level(GeofenceLevel.WORK).build());
        when(geofenceService.listGeofences()).thenReturn(list);

        ApiResult<List<GroundGeofence>> result = controller.listGeofences();

        assertEquals(1, result.data().size());
    }

    @Test
    void validate_happyPath_returnsTrue() {
        when(geofenceService.validatePathInGeofence(anyLong(), anyString())).thenReturn(true);

        ApiResult<Boolean> result = controller.validatePath(new VehicleGeofenceController.ValidatePath(1L, "[]"));

        assertTrue(result.data());
    }

    @Test
    void validate_noWorkZone_mapsTo40970() {
        when(geofenceService.validatePathInGeofence(anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("geofence.no.work.zone"));

        BizException ex = assertThrows(BizException.class,
                () -> controller.validatePath(new VehicleGeofenceController.ValidatePath(1L, "[]")));

        assertEquals(40970, ex.getCode());
    }

    @Test
    void validate_noGo_mapsTo40971() {
        when(geofenceService.validatePathInGeofence(anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("geofence.no.go"));

        BizException ex = assertThrows(BizException.class,
                () -> controller.validatePath(new VehicleGeofenceController.ValidatePath(1L, "[]")));

        assertEquals(40971, ex.getCode());
    }

    @Test
    void validate_outsideWork_mapsTo40972() {
        when(geofenceService.validatePathInGeofence(anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("geofence.outside.work"));

        BizException ex = assertThrows(BizException.class,
                () -> controller.validatePath(new VehicleGeofenceController.ValidatePath(1L, "[]")));

        assertEquals(40972, ex.getCode());
    }

    @Test
    void validate_pathOutOfBounds_mapsTo40973() {
        when(geofenceService.validatePathInGeofence(anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("geofence.path.out.of.bounds:geofence.outside.work"));

        BizException ex = assertThrows(BizException.class,
                () -> controller.validatePath(new VehicleGeofenceController.ValidatePath(1L, "[]")));

        assertEquals(40973, ex.getCode());
    }
}
