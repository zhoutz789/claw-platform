package com.claw.server.domain.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleBatteryBindingServiceTest {

    @Mock
    private VehicleBatteryBindingRepository bindingRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private BatteryRepository batteryRepository;

    @InjectMocks
    private VehicleBatteryBindingService service;

    @Test
    void bind_closesPreviousBindingAndCreatesNew() {
        Long vehicleId = 1L, oldBattery = 10L, newBattery = 20L;
        VehicleBatteryBinding prev = VehicleBatteryBinding.builder()
                .id(100L).vehicleId(vehicleId).batteryId(oldBattery)
                .boundAt(Instant.parse("2026-01-01T00:00:00Z")).unboundAt(null).build();
        when(bindingRepository.findByVehicleIdAndUnboundAtIsNull(vehicleId))
                .thenReturn(Optional.of(prev));
        when(bindingRepository.save(any(VehicleBatteryBinding.class))).thenAnswer(inv -> inv.getArgument(0));
        // 软校验查询回退（未配置协议）
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.empty());
        when(batteryRepository.findById(newBattery)).thenReturn(Optional.empty());

        VehicleBatteryBinding result = service.bind(vehicleId, newBattery, "GB/T");

        // 旧绑定被置 unboundAt
        assertNotNull(prev.getUnboundAt());
        verify(bindingRepository).save(prev);
        // 新绑定创建且生效
        assertEquals(vehicleId, result.getVehicleId());
        assertEquals(newBattery, result.getBatteryId());
        assertNull(result.getUnboundAt());
        assertEquals("GB/T", result.getProtocolVer());
        verify(bindingRepository, times(2)).save(any(VehicleBatteryBinding.class));
    }

    @Test
    void getCurrentBatteryId_returnsBoundBattery() {
        Long vehicleId = 1L, batteryId = 20L;
        when(bindingRepository.findByVehicleIdAndUnboundAtIsNull(vehicleId))
                .thenReturn(Optional.of(VehicleBatteryBinding.builder()
                        .id(1L).vehicleId(vehicleId).batteryId(batteryId)
                        .boundAt(Instant.now()).build()));

        Optional<Long> current = service.getCurrentBatteryId(vehicleId);

        assertTrue(current.isPresent());
        assertEquals(batteryId, current.get());
    }

    @Test
    void unbind_closesCurrentBinding() {
        Long vehicleId = 1L, batteryId = 20L;
        VehicleBatteryBinding prev = VehicleBatteryBinding.builder()
                .id(1L).vehicleId(vehicleId).batteryId(batteryId)
                .boundAt(Instant.parse("2026-01-01T00:00:00Z")).unboundAt(null).build();
        when(bindingRepository.findByVehicleIdAndUnboundAtIsNull(vehicleId))
                .thenReturn(Optional.of(prev));
        when(bindingRepository.save(any(VehicleBatteryBinding.class))).thenAnswer(inv -> inv.getArgument(0));

        service.unbind(vehicleId);

        assertNotNull(prev.getUnboundAt());
        verify(bindingRepository).save(prev);
    }

    @Test
    void getCurrentBatteryId_whenUnbound_returnsEmpty() {
        when(bindingRepository.findByVehicleIdAndUnboundAtIsNull(99L)).thenReturn(Optional.empty());
        assertTrue(service.getCurrentBatteryId(99L).isEmpty());
    }
}
