package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.DriveMode;
import com.claw.server.domain.asset.VehicleBatteryBinding;
import com.claw.server.domain.asset.VehicleBatteryBindingService;
import com.claw.server.domain.asset.VehicleEnergyView;
import com.claw.server.domain.asset.VehicleEnergyViewService;
import com.claw.server.domain.asset.VehicleRepository;
import com.claw.server.domain.autonomy.AutonomyModule;
import com.claw.server.domain.autonomy.AutonomyModuleService;
import com.claw.server.domain.autonomy.AutonomySafetyService;
import com.claw.server.domain.iot.VehicleTrajectory;
import com.claw.server.domain.iot.VehicleTrajectoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VehicleController} 的接线测试（无 Spring 上下文，纯 Mockito）。
 * 覆盖能源三视图、电池绑定视图、最新轨迹（空→null）、自主模块（可空）、驾驶模式切换（成功 / 缺失→40462）、安全锁机。
 */
@ExtendWith(MockitoExtension.class)
class VehicleControllerTest {

    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private VehicleTrajectoryService trajectoryService;
    @Mock
    private VehicleBatteryBindingService batteryBindingService;
    @Mock
    private VehicleEnergyViewService vehicleEnergyViewService;
    @Mock
    private AutonomyModuleService autonomyModuleService;
    @Mock
    private AutonomySafetyService autonomySafetyService;

    @InjectMocks
    private VehicleController controller;

    @Test
    void energy_returnsView() {
        VehicleEnergyView view = VehicleEnergyView.builder()
                .currentBatteryId(5L).recentCharges(List.of()).recentSwaps(List.of()).build();
        when(vehicleEnergyViewService.getVehicleEnergyView(1L)).thenReturn(view);

        ApiResult<VehicleEnergyView> result = controller.getEnergy(1L);

        assertEquals(5L, result.data().getCurrentBatteryId());
    }

    @Test
    void battery_returnsCurrentAndHistory() {
        when(batteryBindingService.getCurrentBatteryId(1L)).thenReturn(Optional.of(7L));
        List<VehicleBatteryBinding> history = List.of(
                VehicleBatteryBinding.builder().vehicleId(1L).batteryId(7L).build());
        when(batteryBindingService.getHistory(1L)).thenReturn(history);

        ApiResult<Map<String, Object>> result = controller.getBattery(1L);

        assertEquals(7L, result.data().get("currentBatteryId"));
        assertEquals(history, result.data().get("history"));
    }

    @Test
    void trajectoryLatest_nullWhenEmpty() {
        when(trajectoryService.getLatest(1L)).thenReturn(Optional.empty());

        ApiResult<VehicleTrajectory> result = controller.getLatestTrajectory(1L);

        assertNull(result.data());
    }

    @Test
    void autonomyModule_nullable() {
        when(autonomyModuleService.getByAssetId(1L)).thenReturn(null);

        ApiResult<AutonomyModule> result = controller.getModule(1L);

        assertNull(result.data());
    }

    @Test
    void setDriveMode_happyPath() {
        AutonomyModule module = AutonomyModule.builder()
                .assetId(1L).algoVersion("v1").driveMode(DriveMode.FULL).build();
        when(autonomyModuleService.setDriveMode(1L, DriveMode.FULL)).thenReturn(module);

        ApiResult<AutonomyModule> result = controller.setDriveMode(1L, new VehicleController.SetDriveMode("FULL"));

        assertEquals(DriveMode.FULL, result.data().getDriveMode());
    }

    @Test
    void setDriveMode_missingModule_throws40462() {
        when(autonomyModuleService.setDriveMode(eq(1L), eq(DriveMode.FULL)))
                .thenThrow(new IllegalArgumentException("autonomy.module.not.found:1"));

        BizException ex = assertThrows(BizException.class,
                () -> controller.setDriveMode(1L, new VehicleController.SetDriveMode("FULL")));

        assertEquals(40462, ex.getCode());
    }

    @Test
    void lockForSafety_callsThrough() {
        controller.lockForSafety(1L);

        verify(autonomySafetyService).lockForSafety(anyLong());
    }
}
