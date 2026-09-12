package com.claw.server.domain.asset;

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
class VehicleProductClassServiceTest {

    @Mock
    private VehicleProductClassRepository classRepository;

    @Mock
    private VehicleScenarioAttrRepository attrRepository;

    @InjectMocks
    private VehicleProductClassService service;

    @Test
    void createClass_persistsWithTimestamps() {
        VehicleProductClass pc = VehicleProductClass.builder()
                .code("LOGISTICS_EBIKE")
                .nameZh("物流两轮车").nameEn("Logistics E-Bike").nameKm("ម៉ូតូឡូស៊ីស្ទិក")
                .scenario("LOGISTICS")
                .capabilityTags("LOGISTICS,AD_DISPLAY")
                .defaultDeviceTypes("VEHICLE_TCU,BMS")
                .build();
        when(classRepository.findByCode("LOGISTICS_EBIKE")).thenReturn(Optional.empty());
        when(classRepository.save(any(VehicleProductClass.class))).thenAnswer(inv -> inv.getArgument(0));

        VehicleProductClass saved = service.createClass(pc);

        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        verify(classRepository).save(pc);
    }

    @Test
    void createClass_throwsWhenCodeExists() {
        when(classRepository.findByCode("DUP")).thenReturn(Optional.of(VehicleProductClass.builder().id(1L).build()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createClass(VehicleProductClass.builder().code("DUP").build()));
        assertTrue(ex.getMessage().contains("vehicle.product.class.code.exists"));
        verify(classRepository, never()).save(any());
    }

    @Test
    void getByCode_throwsWhenMissing() {
        when(classRepository.findByCode("NOPE")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.getByCode("NOPE"));
    }

    @Test
    void addAttr_throwsWhenClassMissing() {
        when(classRepository.existsById(99L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addAttr(99L, VehicleScenarioAttr.builder().attrKey("loadKg").attrType("NUMBER").build()));
        assertTrue(ex.getMessage().contains("vehicle.product.class.not.found"));
        verify(attrRepository, never()).save(any());
    }

    @Test
    void listAttrs_filtersByClass() {
        VehicleScenarioAttr a = VehicleScenarioAttr.builder().id(1L).productClassId(1L).attrKey("loadKg").build();
        VehicleScenarioAttr b = VehicleScenarioAttr.builder().id(2L).productClassId(2L).attrKey("seats").build();
        when(attrRepository.findByProductClassId(1L)).thenReturn(List.of(a, b).stream()
                .filter(x -> x.getProductClassId().equals(1L)).toList());

        assertEquals(1, service.listAttrs(1L).size());
    }
}
