package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.DriveMode;
import com.claw.server.common.enums.SafetyState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutonomyModuleServiceTest {

    @Mock
    private AutonomyModuleRepository moduleRepository;

    @InjectMocks
    private AutonomyModuleService service;

    @Test
    void createModule_defaultsToAssistedAndNormal() {
        when(moduleRepository.save(any(AutonomyModule.class))).thenAnswer(inv -> inv.getArgument(0));

        AutonomyModule m = service.createModule(10L, "v1.2.0", null);

        assertEquals(10L, m.getAssetId());
        assertEquals("v1.2.0", m.getAlgoVersion());
        assertEquals(DriveMode.ASSISTED, m.getDriveMode());
        assertEquals(SafetyState.NORMAL, m.getSafetyState());
    }

    @Test
    void setDriveMode_updatesExistingModule() {
        AutonomyModule existing = AutonomyModule.builder().id(1L).assetId(10L)
                .algoVersion("v1").driveMode(DriveMode.ASSISTED).safetyState(SafetyState.NORMAL).build();
        when(moduleRepository.findAll()).thenReturn(List.of(existing));
        when(moduleRepository.save(any(AutonomyModule.class))).thenAnswer(inv -> inv.getArgument(0));

        AutonomyModule updated = service.setDriveMode(10L, DriveMode.TELEOP);
        assertEquals(DriveMode.TELEOP, updated.getDriveMode());
    }

    @Test
    void setDriveMode_throwsWhenModuleMissing() {
        when(moduleRepository.findAll()).thenReturn(List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.setDriveMode(999L, DriveMode.FULL));
        assertTrue(ex.getMessage().contains("autonomy.module.not.found"));
    }

    @Test
    void getByAssetId_findsModule() {
        AutonomyModule m = AutonomyModule.builder().id(1L).assetId(10L)
                .algoVersion("v1").driveMode(DriveMode.ASSISTED).safetyState(SafetyState.NORMAL).build();
        when(moduleRepository.findAll()).thenReturn(List.of(m));

        assertNotNull(service.getByAssetId(10L));
        assertNull(service.getByAssetId(20L));
    }
}
