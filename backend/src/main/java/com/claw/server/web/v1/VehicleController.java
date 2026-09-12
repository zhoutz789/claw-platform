package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.DriveMode;
import com.claw.server.common.enums.InteractionDirection;
import com.claw.server.domain.asset.Vehicle;
import com.claw.server.domain.asset.VehicleBatteryBindingService;
import com.claw.server.domain.asset.VehicleEnergyView;
import com.claw.server.domain.asset.VehicleEnergyViewService;
import com.claw.server.domain.asset.VehicleRepository;
import com.claw.server.domain.autonomy.AutonomyModule;
import com.claw.server.domain.autonomy.AutonomyModuleService;
import com.claw.server.domain.autonomy.AutonomySafetyEvent;
import com.claw.server.domain.autonomy.AutonomySafetyService;
import com.claw.server.domain.iot.VehicleTrajectory;
import com.claw.server.domain.iot.VehicleTrajectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 车辆资产视图与自主能力端口（T8）。
 *
 * <p>车辆资产不存在时原仓储返回 empty，这里统一转 40465（HTTP 404）。
 * 自主模块不存在时服务抛裸 {@code IllegalArgumentException("autonomy.module.not.found:..")}，
 * 转 40462（HTTP 404）。
 */
@RestController
@RequestMapping("/api/v1/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleRepository vehicleRepository;
    private final VehicleTrajectoryService trajectoryService;
    private final VehicleBatteryBindingService batteryBindingService;
    private final VehicleEnergyViewService vehicleEnergyViewService;
    private final AutonomyModuleService autonomyModuleService;
    private final AutonomySafetyService autonomySafetyService;

    /**
     * 取车辆资产。
     *
     * @throws BizException 40465 error.vehicle.not.found（资产不存在）
     */
    @GetMapping("/{vehicleId}")
    public ApiResult<Vehicle> getVehicle(@PathVariable Long vehicleId) {
        Vehicle v = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> BizException.of(40465, "error.vehicle.not.found", vehicleId));
        return ApiResult.ok(v);
    }

    /** 车辆能源三视图（当前电池 / 近期充电 / 近期换电）。 */
    @GetMapping("/{vehicleId}/energy")
    public ApiResult<VehicleEnergyView> getEnergy(@PathVariable Long vehicleId) {
        return ApiResult.ok(vehicleEnergyViewService.getVehicleEnergyView(vehicleId));
    }

    /** 当前绑定电池 + 绑定历史。 */
    @GetMapping("/{vehicleId}/battery")
    public ApiResult<Map<String, Object>> getBattery(@PathVariable Long vehicleId) {
        // 注意：未绑定电池时 currentBatteryId 为 null，HashMap 允许 null 值；
        // 切勿用 Map.of（ImmutableCollections 拒绝 null，会抛 NPE → HTTP 500）。
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("currentBatteryId", batteryBindingService.getCurrentBatteryId(vehicleId).orElse(null));
        result.put("history", batteryBindingService.getHistory(vehicleId));
        return ApiResult.ok(result);
    }

    /**
     * 车辆轨迹（按时间窗）。from/to 为 ISO-8601；二者任一为空或解析失败则回落到
     * 「现在 − 24h .. 现在」默认窗口。
     */
    @GetMapping("/{vehicleId}/trajectory")
    public ApiResult<List<VehicleTrajectory>> getTrajectory(@PathVariable Long vehicleId,
                                                            @RequestParam(required = false) String from,
                                                            @RequestParam(required = false) String to) {
        Instant now = Instant.now();
        Instant fromInstant = now.minus(24, ChronoUnit.HOURS);
        Instant toInstant = now;
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            try {
                fromInstant = Instant.parse(from);
                toInstant = Instant.parse(to);
            } catch (RuntimeException ex) {
                // 解析失败 → 回落默认窗口
                fromInstant = now.minus(24, ChronoUnit.HOURS);
                toInstant = now;
            }
        }
        return ApiResult.ok(trajectoryService.getTrajectory(vehicleId, fromInstant, toInstant));
    }

    /** 最新轨迹点（无轨迹时返回 null）。 */
    @GetMapping("/{vehicleId}/trajectory/latest")
    public ApiResult<VehicleTrajectory> getLatestTrajectory(@PathVariable Long vehicleId) {
        Optional<VehicleTrajectory> latest = trajectoryService.getLatest(vehicleId);
        return ApiResult.ok(latest.orElse(null));
    }

    /** 取车辆自主模块（未挂载则返回 null）。 */
    @GetMapping("/{vehicleId}/autonomy/module")
    public ApiResult<AutonomyModule> getModule(@PathVariable Long vehicleId) {
        return ApiResult.ok(autonomyModuleService.getByAssetId(vehicleId));
    }

    /** 若未挂载自主模块则创建（幂等）。 */
    @PostMapping("/{vehicleId}/autonomy/module")
    public ApiResult<AutonomyModule> createModule(@PathVariable Long vehicleId,
                                                  @RequestBody CreateModule req) {
        AutonomyModule module = autonomyModuleService.createModuleIfAbsent(
                vehicleId, req.algoVersion(), DriveMode.valueOf(req.driveMode()));
        return ApiResult.ok(module);
    }

    /**
     * 切换驾驶模式。
     *
     * @throws BizException 40462 error.vehicle.autonomy.module.not.found（模块不存在）
     */
    @PostMapping("/{vehicleId}/autonomy/drive-mode")
    public ApiResult<AutonomyModule> setDriveMode(@PathVariable Long vehicleId,
                                                  @RequestBody SetDriveMode req) {
        try {
            return ApiResult.ok(autonomyModuleService.setDriveMode(vehicleId, DriveMode.valueOf(req.driveMode())));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("autonomy.module.not.found")) {
                throw BizException.of(40462, "error.vehicle.autonomy.module.not.found", vehicleId);
            }
            throw ex;
        }
    }

    /** 进入遥操作（切 TELEOP + 播报）。 */
    @PostMapping("/{vehicleId}/autonomy/teleop/enter")
    public ApiResult<AutonomyModule> enterTeleop(@PathVariable Long vehicleId) {
        return ApiResult.ok(autonomyModuleService.enterTeleop(vehicleId));
    }

    /** 退出遥操作（切回 ASSISTED）。 */
    @PostMapping("/{vehicleId}/autonomy/teleop/exit")
    public ApiResult<AutonomyModule> exitTeleop(@PathVariable Long vehicleId) {
        return ApiResult.ok(autonomyModuleService.exitTeleop(vehicleId));
    }

    /** 记录一条语音交互（提醒路人 / 接收指令）。 */
    @PostMapping("/{vehicleId}/autonomy/voice")
    public ApiResult<Void> logVoice(@PathVariable Long vehicleId, @RequestBody VoiceReq req) {
        autonomyModuleService.logVoice(vehicleId, InteractionDirection.valueOf(req.direction()),
                req.text(), req.lang());
        return ApiResult.ok();
    }

    /** 取车辆安全事件列表。 */
    @GetMapping("/{vehicleId}/autonomy/safety")
    public ApiResult<List<AutonomySafetyEvent>> getSafety(@PathVariable Long vehicleId) {
        return ApiResult.ok(autonomySafetyService.getByAsset(vehicleId));
    }

    /** 安全锁机（置 LOCKED + 下发锁车指令）。 */
    @PostMapping("/{vehicleId}/autonomy/safety/lock")
    public ApiResult<Void> lockForSafety(@PathVariable Long vehicleId) {
        autonomySafetyService.lockForSafety(vehicleId);
        return ApiResult.ok();
    }

    public record CreateModule(String algoVersion, String driveMode) {
    }

    public record SetDriveMode(String driveMode) {
    }

    public record VoiceReq(String direction, String text, String lang) {
    }
}
