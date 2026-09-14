package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTViews;
import com.claw.server.common.enums.DroneCommandType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DroneCommandServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceCommandService deviceCommandService;

    @InjectMocks
    private DroneCommandService service;

    private static IoTViews.CommandView view(String action) {
        return new IoTViews.CommandView(1L, "FCU-1", action, "cmd-1", "PENDING", null, null, Instant.now(), null);
    }

    @Test
    void remoteStart_resolvesFcuAndIssuesAction() {
        Device fcu = Device.builder().assetId(10L).deviceType("DRONE_FCU").deviceNo("FCU-1").build();
        when(deviceRepository.findByAssetId(10L)).thenReturn(List.of(fcu));
        when(deviceCommandService.issue(eq("FCU-1"), eq("drone.remote_start"), any()))
                .thenReturn(view("drone.remote_start"));

        IoTViews.CommandView result = service.remoteStart(10L);

        assertEquals("drone.remote_start", result.action());
        verify(deviceCommandService).issue(eq("FCU-1"), eq("drone.remote_start"), any());
    }

    @Test
    void issue_throwsWhenNoFcu() {
        when(deviceRepository.findByAssetId(99L)).thenReturn(List.of(
                Device.builder().assetId(99L).deviceType("CAMERA").build()));

        BizException ex = assertThrows(BizException.class, () -> service.hold(99L));

        assertTrue(ex.getMessageCode().contains("error.drone.fcu.not.found"));
    }

    @Test
    void setGeofence_carriesGeofenceParam() {
        Device fcu = Device.builder().assetId(10L).deviceType("DRONE_FCU").deviceNo("FCU-1").build();
        when(deviceRepository.findByAssetId(10L)).thenReturn(List.of(fcu));
        when(deviceCommandService.issue(eq("FCU-1"), eq("drone.set_geofence"), any()))
                .thenReturn(view("drone.set_geofence"));

        service.setGeofence(10L, "POLYGON((...))");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(deviceCommandService).issue(eq("FCU-1"), eq("drone.set_geofence"), captor.capture());
        assertEquals("POLYGON((...))", captor.getValue().get("geofence"));
    }

    @Test
    void commandType_resolvesByNameAndAction() {
        assertEquals(DroneCommandType.REMOTE_START, DroneCommandType.fromCode("REMOTE_START"));
        assertEquals(DroneCommandType.REMOTE_START, DroneCommandType.fromCode("drone.remote_start"));
        assertThrows(IllegalArgumentException.class, () -> DroneCommandType.fromCode("NOPE"));
        assertThrows(IllegalArgumentException.class, () -> DroneCommandType.fromCode(null));
    }
}
