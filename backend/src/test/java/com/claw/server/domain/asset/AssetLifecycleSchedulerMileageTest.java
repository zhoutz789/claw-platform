package com.claw.server.domain.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.claw.server.domain.iot.TelemetryRepository;
import com.claw.server.domain.iot.VehicleTrajectoryRepository;
import com.claw.server.domain.settings.SystemConfigRepository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资产里程退役判定单测：仅覆盖从 {@link AssetLifecycleScheduler} 抽出的纯函数判定，
 * 不装配整个调度器扫描逻辑（阈值/轨迹读取走真实仓储，由集成测试覆盖）。
 */
@ExtendWith(MockitoExtension.class)
class AssetLifecycleSchedulerMileageTest {

    @Mock
    private AssetRepository assetRepository;
    @Mock
    private BatteryRepository batteryRepository;
    @Mock
    private DroneRepository droneRepository;
    @Mock
    private TelemetryRepository telemetryRepository;
    @Mock
    private AssetLifecycleEventRepository lifecycleEventRepository;
    @Mock
    private AssetService assetService;
    @Mock
    private VehicleTrajectoryRepository vehicleTrajectoryRepository;
    @Mock
    private SystemConfigRepository systemConfigRepository;

    @InjectMocks
    private AssetLifecycleScheduler scheduler;

    @Test
    void isMileageRetire_trueWhenAtOrAboveThreshold() {
        assertTrue(scheduler.isMileageRetire(200000L, 200000L));
        assertTrue(scheduler.isMileageRetire(300000L, 200000L));
    }

    @Test
    void isMileageRetire_falseWhenBelowOrNull() {
        assertFalse(scheduler.isMileageRetire(199999L, 200000L));
        assertFalse(scheduler.isMileageRetire(null, 200000L));
    }
}
