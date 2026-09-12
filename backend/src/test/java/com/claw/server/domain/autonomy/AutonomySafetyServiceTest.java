package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.SafetyState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutonomySafetyServiceTest {

    @Mock
    private AutonomySafetyEventRepository eventRepository;

    @Mock
    private AutonomyModuleService autonomyModuleService;

    @InjectMocks
    private AutonomySafetyService service;

    @Test
    void recordEvent_criticalLocksModule() {
        when(eventRepository.save(any(AutonomySafetyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent(1L, "OBSTACLE", "CRITICAL", "{}");

        verify(autonomyModuleService).setSafetyState(eq(1L), eq(SafetyState.LOCKED));
    }

    @Test
    void recordEvent_warnDoesNotLock() {
        when(eventRepository.save(any(AutonomySafetyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent(2L, "WEATHER", "WARN", "{}");

        verify(autonomyModuleService, never()).setSafetyState(any(), any());
    }

    @Test
    void recordEvent_lockCauseLocksModuleEvenIfWarn() {
        when(eventRepository.save(any(AutonomySafetyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent(3L, "GEOFENCE", "WARN", "{}");

        verify(autonomyModuleService).setSafetyState(eq(3L), eq(SafetyState.LOCKED));
    }
}
