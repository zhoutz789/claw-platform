package com.claw.server.domain.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleProductClassServiceSeedTest {

    @Mock
    private VehicleProductClassRepository classRepository;

    @Mock
    private VehicleScenarioAttrRepository attrRepository;

    @InjectMocks
    private VehicleProductClassService service;

    @Test
    void seedDefaultProfiles_insertsEightWhenEmpty() {
        when(classRepository.count()).thenReturn(0L);
        when(classRepository.save(any(VehicleProductClass.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attrRepository.save(any(VehicleScenarioAttr.class))).thenAnswer(inv -> inv.getArgument(0));

        int n = service.seedDefaultProfiles();

        assertEquals(8, n);
        verify(classRepository, times(8)).save(any(VehicleProductClass.class));
        // 各车型属性数：2+1+2+1+1+1+1+1 = 10
        verify(attrRepository, times(10)).save(any(VehicleScenarioAttr.class));
    }

    @Test
    void seedDefaultProfiles_isIdempotentWhenNotEmpty() {
        when(classRepository.count()).thenReturn(3L);

        int n = service.seedDefaultProfiles();

        assertEquals(0, n);
        verify(classRepository, never()).save(any(VehicleProductClass.class));
        verify(attrRepository, never()).save(any(VehicleScenarioAttr.class));
    }
}
