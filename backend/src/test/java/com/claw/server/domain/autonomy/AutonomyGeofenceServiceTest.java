package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.GeofenceLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutonomyGeofenceServiceTest {

    @Mock
    private GroundGeofenceRepository geofenceRepository;

    @InjectMocks
    private AutonomyGeofenceService service;

    private GroundGeofence workZone() {
        return GroundGeofence.builder()
                .id(1L).name("work").level(GeofenceLevel.WORK)
                .polygonJson("[[103.9,10.9],[104.1,10.9],[104.1,11.1],[103.9,11.1],[103.9,10.9]]")
                .build();
    }

    private GroundGeofence noGoZone() {
        return GroundGeofence.builder()
                .id(2L).name("nogo").level(GeofenceLevel.NO_GO)
                .polygonJson("[[104.9,10.9],[105.1,10.9],[105.1,11.1],[104.9,11.1],[104.9,10.9]]")
                .build();
    }

    @Test
    void assertInsideGeofence_passesInsideWorkAndOutsideNoGo() {
        when(geofenceRepository.findAll()).thenReturn(List.of(workZone(), noGoZone()));

        assertDoesNotThrow(() -> service.assertInsideGeofence(1L, 11.0, 104.0));
    }

    @Test
    void assertInsideGeofence_throwsOnNoGo() {
        when(geofenceRepository.findAll()).thenReturn(List.of(workZone(), noGoZone()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assertInsideGeofence(1L, 11.0, 105.0));
        assertTrue(ex.getMessage().contains("geofence.no.go"));
    }

    @Test
    void assertInsideGeofence_throwsOutsideAllWork() {
        when(geofenceRepository.findAll()).thenReturn(List.of(workZone(), noGoZone()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assertInsideGeofence(1L, 20.0, 105.0));
        assertTrue(ex.getMessage().contains("geofence.outside.work"));
    }

    @Test
    void assertInsideGeofence_throwsWhenNoWorkZone() {
        when(geofenceRepository.findAll()).thenReturn(List.of(noGoZone()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assertInsideGeofence(1L, 11.0, 104.0));
        assertTrue(ex.getMessage().contains("geofence.no.work.zone"));
    }
}
