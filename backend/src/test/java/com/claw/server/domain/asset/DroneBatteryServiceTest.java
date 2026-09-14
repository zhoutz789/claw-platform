package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DroneBatteryService} 单元测试（切片 2 · V140）。
 *
 * <p>覆盖：绑定前置校验（404/400 而非外键 500）、闭合旧绑定语义、解绑幂等、供需视图取数。
 */
@ExtendWith(MockitoExtension.class)
class DroneBatteryServiceTest {

    private static final Long DRONE_ID = 10L;
    private static final Long BATTERY_ID = 20L;

    @Mock
    private DroneBatteryBindingRepository bindingRepository;

    @Mock
    private DroneRepository droneRepository;

    @Mock
    private BatteryRepository batteryRepository;

    @InjectMocks
    private DroneBatteryService service;

    private static Drone drone() {
        return Drone.builder().assetId(DRONE_ID).remoteId("RID-1").model("M-1").build();
    }

    private static Battery battery() {
        return Battery.builder().assetId(BATTERY_ID).build();
    }

    private static DroneBatteryBinding activeBinding(Long batteryId) {
        return DroneBatteryBinding.builder()
                .id(1L)
                .droneAssetId(DRONE_ID)
                .batteryAssetId(batteryId)
                .boundAt(Instant.now())
                .cycles(3)
                .active(true)
                .build();
    }

    @Test
    void bind_closesPreviousBindingAndCreatesActiveOne() {
        DroneBatteryBinding previous = activeBinding(99L);
        when(droneRepository.findByAssetId(DRONE_ID)).thenReturn(Optional.of(drone()));
        when(batteryRepository.findByAssetId(BATTERY_ID)).thenReturn(Optional.of(battery()));
        when(bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(DRONE_ID))
                .thenReturn(Optional.of(previous));
        when(bindingRepository.save(any(DroneBatteryBinding.class))).thenAnswer(inv -> inv.getArgument(0));

        DroneBatteryBinding result = service.bind(DRONE_ID, BATTERY_ID, 5);

        assertFalse(previous.getActive(), "旧绑定必须被关闭");
        assertNotNull(previous.getUnboundAt(), "旧绑定必须落解绑时间");
        assertEquals(BATTERY_ID, result.getBatteryAssetId());
        assertTrue(result.getActive());
        assertEquals(5, result.getCycles());
        assertNotNull(result.getBoundAt());
        verify(bindingRepository, times(2)).save(any(DroneBatteryBinding.class));
    }

    @Test
    void bind_defaultsNullCyclesToZero() {
        when(droneRepository.findByAssetId(DRONE_ID)).thenReturn(Optional.of(drone()));
        when(batteryRepository.findByAssetId(BATTERY_ID)).thenReturn(Optional.of(battery()));
        when(bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(DRONE_ID))
                .thenReturn(Optional.empty());
        when(bindingRepository.save(any(DroneBatteryBinding.class))).thenAnswer(inv -> inv.getArgument(0));

        DroneBatteryBinding result = service.bind(DRONE_ID, BATTERY_ID, null);

        assertEquals(0, result.getCycles());
    }

    @Test
    void bind_rejectsNullArguments() {
        BizException ex = assertThrows(BizException.class, () -> service.bind(null, BATTERY_ID, 0));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void bind_stillSucceedsWhenBatteryAlreadyInUseElsewhere() {
        when(droneRepository.findByAssetId(DRONE_ID)).thenReturn(Optional.of(drone()));
        when(batteryRepository.findByAssetId(BATTERY_ID)).thenReturn(Optional.of(battery()));
        when(bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(DRONE_ID))
                .thenReturn(Optional.empty());
        when(bindingRepository.existsByBatteryAssetIdAndActiveTrue(BATTERY_ID)).thenReturn(true);
        when(bindingRepository.save(any(DroneBatteryBinding.class))).thenAnswer(inv -> inv.getArgument(0));

        DroneBatteryBinding result = service.bind(DRONE_ID, BATTERY_ID, 0);

        // 软校验：重复占用只告警，不阻断换电作业。
        assertTrue(result.getActive());
        verify(bindingRepository).existsByBatteryAssetIdAndActiveTrue(BATTERY_ID);
    }

    @Test
    void bind_survivesSharingSoftCheckFailure() {
        when(droneRepository.findByAssetId(DRONE_ID)).thenReturn(Optional.of(drone()));
        when(batteryRepository.findByAssetId(BATTERY_ID)).thenReturn(Optional.of(battery()));
        when(bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(DRONE_ID))
                .thenReturn(Optional.empty());
        when(bindingRepository.existsByBatteryAssetIdAndActiveTrue(BATTERY_ID))
                .thenThrow(new IllegalStateException("check unavailable"));
        when(bindingRepository.save(any(DroneBatteryBinding.class))).thenAnswer(inv -> inv.getArgument(0));

        DroneBatteryBinding result = service.bind(DRONE_ID, BATTERY_ID, 0);

        // 观测性校验失败不得影响既有的绑定主流程。
        assertTrue(result.getActive());
    }

    @Test
    void bind_rejectsMissingDroneWith404() {
        when(droneRepository.findByAssetId(DRONE_ID)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.bind(DRONE_ID, BATTERY_ID, 0));

        assertEquals(40466, ex.getCode());
        assertEquals("error.drone.not.found", ex.getMessageCode());
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void bind_rejectsMissingBatteryWith404() {
        when(droneRepository.findByAssetId(DRONE_ID)).thenReturn(Optional.of(drone()));
        when(batteryRepository.findByAssetId(BATTERY_ID)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.bind(DRONE_ID, BATTERY_ID, 0));

        assertEquals(40467, ex.getCode());
        assertEquals("error.battery.not.found", ex.getMessageCode());
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void unbind_returnsFalseWhenNoActiveBinding() {
        when(bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(DRONE_ID))
                .thenReturn(Optional.empty());

        assertFalse(service.unbind(DRONE_ID));
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void unbind_closesActiveBinding() {
        DroneBatteryBinding current = activeBinding(BATTERY_ID);
        when(bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(DRONE_ID))
                .thenReturn(Optional.of(current));
        when(bindingRepository.save(any(DroneBatteryBinding.class))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(service.unbind(DRONE_ID));

        assertFalse(current.getActive());
        assertNotNull(current.getUnboundAt());
    }

    @Test
    void currentBattery_returnsBoundBattery() {
        when(bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(DRONE_ID))
                .thenReturn(Optional.of(activeBinding(BATTERY_ID)));

        assertEquals(Optional.of(BATTERY_ID), service.currentBattery(DRONE_ID));
    }

    @Test
    void currentBattery_returnsEmptyWhenUnbound() {
        when(bindingRepository.findFirstByDroneAssetIdAndActiveTrueOrderByBoundAtDesc(DRONE_ID))
                .thenReturn(Optional.empty());

        assertEquals(Optional.empty(), service.currentBattery(DRONE_ID));
    }

    @Test
    void supplyDemandView_computesAvailableFromTotals() {
        when(batteryRepository.count()).thenReturn(10L);
        when(bindingRepository.countByActiveTrue()).thenReturn(4L);

        Map<String, Object> view = service.supplyDemandView();

        assertEquals(10L, view.get("batteryTotal"));
        assertEquals(4L, view.get("batteryInUse"));
        assertEquals(6L, view.get("batteryAvailable"));
        assertEquals(4L, view.get("droneBound"));
        assertNotNull(view.get("generatedAt"));
    }

    @Test
    void supplyDemandView_neverReturnsNegativeAvailable() {
        when(batteryRepository.count()).thenReturn(1L);
        when(bindingRepository.countByActiveTrue()).thenReturn(5L);

        Map<String, Object> view = service.supplyDemandView();

        assertEquals(0L, view.get("batteryAvailable"));
    }
}
