package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTViews;
import com.claw.server.common.enums.VehicleCommandType;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceCommandService;
import com.claw.server.domain.iot.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleCommandServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceCommandService deviceCommandService;

    @InjectMocks
    private VehicleCommandService service;

    private static Device tcu(String deviceNo) {
        return Device.builder().id(1L).assetId(1L).deviceType("VEHICLE_TCU").deviceNo(deviceNo).build();
    }

    private static IoTViews.CommandView view(String action) {
        return new IoTViews.CommandView(10L, "TCU-1", action, "cmd-x", "PENDING", null, null, Instant.now(), null);
    }

    @Test
    void lock_issuesLockActionToTcu() {
        when(deviceRepository.findByAssetId(1L)).thenReturn(List.of(tcu("TCU-1")));
        when(deviceCommandService.issue(eq("TCU-1"), eq("lock"), any())).thenReturn(view("lock"));

        IoTViews.CommandView v = service.lock(1L);

        assertEquals("lock", v.action());
        verify(deviceCommandService).issue(eq("TCU-1"), eq("lock"), any());
    }

    @Test
    void unlock_issuesUnlockAction() {
        when(deviceRepository.findByAssetId(1L)).thenReturn(List.of(tcu("TCU-1")));
        when(deviceCommandService.issue(eq("TCU-1"), eq("unlock"), any())).thenReturn(view("unlock"));

        service.unlock(1L);
        verify(deviceCommandService).issue(eq("TCU-1"), eq("unlock"), any());
    }

    @Test
    void setAc_passesTempCParam() {
        when(deviceRepository.findByAssetId(1L)).thenReturn(List.of(tcu("TCU-1")));
        when(deviceCommandService.issue(eq("TCU-1"), eq("set_ac"), any())).thenReturn(view("set_ac"));
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        service.setAc(1L, 22.5);

        verify(deviceCommandService).issue(eq("TCU-1"), eq("set_ac"), captor.capture());
        assertEquals(22.5, captor.getValue().get("tempC"));
    }

    @Test
    void setGeofence_passesPolygonParam() {
        when(deviceRepository.findByAssetId(1L)).thenReturn(List.of(tcu("TCU-1")));
        when(deviceCommandService.issue(eq("TCU-1"), eq("set_geofence"), any())).thenReturn(view("set_geofence"));
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        service.setGeofence(1L, "POLY-A");

        verify(deviceCommandService).issue(eq("TCU-1"), eq("set_geofence"), captor.capture());
        assertEquals("POLY-A", captor.getValue().get("polygon"));
    }

    @Test
    void lockForPaymentDefault_marksReason() {
        when(deviceRepository.findByAssetId(1L)).thenReturn(List.of(tcu("TCU-1")));
        when(deviceCommandService.issue(eq("TCU-1"), eq("lock"), any())).thenReturn(view("lock"));
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        service.lockForPaymentDefault(1L);

        verify(deviceCommandService).issue(eq("TCU-1"), eq("lock"), captor.capture());
        assertEquals("PAYMENT_DEFAULT", captor.getValue().get("reason"));
    }

    @Test
    void resolveTcu_throwsWhenNoTcu() {
        when(deviceRepository.findByAssetId(1L)).thenReturn(
                List.of(Device.builder().id(2L).assetId(1L).deviceType("BMS").deviceNo("BMS-1").build()));

        BizException ex = assertThrows(BizException.class, () -> service.lock(1L));
        assertTrue(ex.getMessage().contains("error.vehicle.tcu.not.found"));
        verify(deviceCommandService, never()).issue(anyString(), anyString(), any());
    }
}
