package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.DriveMode;
import com.claw.server.common.enums.InteractionDirection;
import com.claw.server.common.enums.SafetyState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutonomyModuleServiceAu6Test {

    @Mock
    private AutonomyModuleRepository moduleRepository;

    @Mock
    private VoiceInteractionRepository voiceInteractionRepository;

    @InjectMocks
    private AutonomyModuleService service;

    @Test
    void enterTeleop_setsTeleopAndPersistsOutVoiceInteraction() {
        AutonomyModule module = AutonomyModule.builder()
                .id(1L).assetId(42L)
                .driveMode(DriveMode.ASSISTED).safetyState(SafetyState.NORMAL).build();
        when(moduleRepository.findAll()).thenReturn(List.of(module));
        when(moduleRepository.save(any(AutonomyModule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.enterTeleop(42L);

        assertEquals(DriveMode.TELEOP, module.getDriveMode());
        ArgumentCaptor<VoiceInteraction> captor = ArgumentCaptor.forClass(VoiceInteraction.class);
        verify(voiceInteractionRepository).save(captor.capture());
        VoiceInteraction saved = captor.getValue();
        assertEquals(42L, saved.getAssetId());
        assertEquals(InteractionDirection.OUT, saved.getDirection());
        assertEquals("teleop.enter", saved.getText());
    }

    @Test
    void exitTeleop_setsAssisted() {
        AutonomyModule module = AutonomyModule.builder()
                .id(1L).assetId(42L)
                .driveMode(DriveMode.TELEOP).safetyState(SafetyState.NORMAL).build();
        when(moduleRepository.findAll()).thenReturn(List.of(module));
        when(moduleRepository.save(any(AutonomyModule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.exitTeleop(42L);

        assertEquals(DriveMode.ASSISTED, module.getDriveMode());
    }

    @Test
    void setSafetyState_setsLocked() {
        AutonomyModule module = AutonomyModule.builder()
                .id(1L).assetId(42L)
                .driveMode(DriveMode.ASSISTED).safetyState(SafetyState.NORMAL).build();
        when(moduleRepository.findAll()).thenReturn(List.of(module));
        when(moduleRepository.save(any(AutonomyModule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setSafetyState(42L, SafetyState.LOCKED);

        assertEquals(SafetyState.LOCKED, module.getSafetyState());
        verify(moduleRepository).save(module);
    }

    @Test
    void setSafetyState_throwsWhenModuleMissing() {
        when(moduleRepository.findAll()).thenReturn(List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.setSafetyState(99L, SafetyState.LOCKED));
        assertTrue(ex.getMessage().contains("autonomy.module.not.found"));
    }
}
