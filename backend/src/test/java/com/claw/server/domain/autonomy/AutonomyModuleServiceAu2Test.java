package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.DriveMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutonomyModuleServiceAu2Test {

    @Mock
    private AutonomyModuleRepository moduleRepository;

    @Mock
    private VoiceInteractionRepository voiceInteractionRepository;

    @InjectMocks
    private AutonomyModuleService service;

    @Test
    void createModuleIfAbsent_isIdempotent() {
        List<AutonomyModule> store = new ArrayList<>();
        when(moduleRepository.findAll()).thenAnswer(inv -> new ArrayList<>(store));
        when(moduleRepository.save(any(AutonomyModule.class))).thenAnswer(inv -> {
            AutonomyModule m = inv.getArgument(0);
            store.add(m);
            return m;
        });

        AutonomyModule first = service.createModuleIfAbsent(42L, "v1", DriveMode.ASSISTED);
        AutonomyModule second = service.createModuleIfAbsent(42L, "v1", DriveMode.ASSISTED);

        assertSame(first, second);
        verify(moduleRepository, times(1)).save(any(AutonomyModule.class));
    }

    @Test
    void provisionForNewAutonomousAsset_createsAssistedModule() {
        when(moduleRepository.findAll()).thenReturn(List.of());
        when(moduleRepository.save(any(AutonomyModule.class))).thenAnswer(inv -> inv.getArgument(0));

        AutonomyModule created = service.provisionForNewAutonomousAsset(42L);

        assertEquals(42L, created.getAssetId());
        assertEquals(DriveMode.ASSISTED, created.getDriveMode());
        verify(moduleRepository).save(any(AutonomyModule.class));
    }
}
