package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTViews;
import com.claw.server.common.enums.CapacityPlanStatus;
import com.claw.server.common.enums.CapacityType;
import com.claw.server.common.enums.DroneCommandType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.airspace.PilotBehaviorEvent;
import com.claw.server.domain.airspace.PilotOpsService;
import com.claw.server.domain.airspace.PilotPenalty;
import com.claw.server.domain.airspace.PilotProfile;
import com.claw.server.domain.asset.DroneBatteryBinding;
import com.claw.server.domain.asset.DroneBatteryService;
import com.claw.server.domain.asset.DroneCommandService;
import com.claw.server.domain.asset.DroneProductClass;
import com.claw.server.domain.asset.DroneProductClassService;
import com.claw.server.domain.capacity.CapacityPlan;
import com.claw.server.domain.capacity.CapacityPlanRepository;
import com.claw.server.domain.iot.DroneTrajectory;
import com.claw.server.domain.iot.DroneTrajectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 无人机作业管控（切片 1 + 切片 2）。
 *
 * <p>端点（前缀 /api/v1/admin/drone）：
 * <ul>
 *   <li><b>切片 1</b>
 *     <ul>
 *       <li>GET  /product-classes                          机型产品类列表（读）</li>
 *       <li>GET  /assets/{assetId}/trajectory?from=&to=   航迹回放（读）</li>
 *       <li>POST /assets/{assetId}/command                远控指令下发（写）</li>
 *     </ul>
 *   </li>
 *   <li><b>切片 2 · 电池绑定（V140）</b>
 *     <ul>
 *       <li>GET    /assets/{assetId}/battery              当前绑定 + 绑定历史（读）</li>
 *       <li>POST   /assets/{assetId}/battery              绑定电池（写）</li>
 *       <li>DELETE /assets/{assetId}/battery              解绑当前电池（写）</li>
 *       <li>GET    /battery/supply-demand                 电池供需视图（读）</li>
 *     </ul>
 *   </li>
 *   <li><b>切片 2 · 飞手运营（V141）</b>
 *     <ul>
 *       <li>GET  /pilots                                  飞手档案列表（读）</li>
 *       <li>POST /pilots                                  提交飞手建档（写）</li>
 *       <li>POST /pilots/{userId}/approve                 审核通过（写）</li>
 *       <li>GET  /pilots/{userId}/behaviors               行为事件（读）</li>
 *       <li>GET  /pilots/{userId}/penalties               处罚历史（读）</li>
 *       <li>POST /pilots/{userId}/penalties               施加处罚（写）</li>
 *     </ul>
 *   </li>
 *   <li><b>切片 2 · 容量（V142）</b>
 *     <ul>
 *       <li>GET  /capacity-plans                          无人机容量产品（PARALLEL，读）</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><b>权限约定</b>：只写接口（POST/DELETE）带 {@link RequirePermission}；GET 读接口一律不带
 * （与 Slice 1 回修后的项目约定一致）。权限位由 V139（切片 1）与 V141（切片 2）以三步法播种。
 *
 * <p>远控经 {@link DroneCommandService} → {@code DeviceCommandService}（HMAC 签名 + 审计落库 + 下发），
 * 复用既有 {@code device_commands}，不另造指令日志表。
 */
@RestController
@RequestMapping("/api/v1/admin/drone")
@RequiredArgsConstructor
public class DroneOpsController {

    private final DroneProductClassService droneProductClassService;
    private final DroneTrajectoryService droneTrajectoryService;
    private final DroneCommandService droneCommandService;
    private final DroneBatteryService droneBatteryService;
    private final PilotOpsService pilotOpsService;
    private final CapacityPlanRepository capacityPlanRepository;

    // ------------------------------------------------------------------ 切片 1

    /** 机型产品类列表（可按场景过滤）。读接口按项目约定不加权限注解。 */
    @GetMapping("/product-classes")
    public ApiResult<List<DroneProductClass>> productClasses(
            @RequestParam(required = false) String scenario) {
        return ApiResult.ok(droneProductClassService.list(scenario));
    }

    /** 航迹回放：按资产 + 时间窗返回航迹点（升序）。from/to 缺省时取全时段。读接口按约定不加权限注解。 */
    @GetMapping("/assets/{assetId}/trajectory")
    public ApiResult<List<DroneTrajectory>> trajectory(
            @PathVariable Long assetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant f = from != null ? from : Instant.EPOCH;
        Instant t = to != null ? to : Instant.now();
        return ApiResult.ok(droneTrajectoryService.query(assetId, f, t));
    }

    /** 远控指令下发。body: {"command":"REMOTE_START","params":{...}}。 */
    @RequirePermission("drone:command:issue")
    @PostMapping("/assets/{assetId}/command")
    public ApiResult<IoTViews.CommandView> command(@PathVariable Long assetId,
                                                   @RequestBody CommandReq req) {
        DroneCommandType type;
        try {
            type = DroneCommandType.fromCode(req.command());
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("error.drone.command.invalid");
        }
        Map<String, Object> params = req.params() != null ? req.params() : Map.of();
        return ApiResult.ok(droneCommandService.issue(assetId, type, params));
    }

    // --------------------------------------------------------- 切片 2 · 电池绑定

    /**
     * 当前绑定电池 + 绑定历史。
     *
     * <p>注意：未绑定电池时 {@code currentBatteryId} 为 null，HashMap 允许 null 值；
     * 切勿用 {@code Map.of}（ImmutableCollections 拒绝 null，会抛 NPE → HTTP 500）。
     */
    @GetMapping("/assets/{assetId}/battery")
    public ApiResult<Map<String, Object>> battery(@PathVariable Long assetId) {
        Map<String, Object> result = new HashMap<>();
        result.put("currentBatteryId", droneBatteryService.currentBattery(assetId).orElse(null));
        result.put("history", droneBatteryService.history(assetId));
        return ApiResult.ok(result);
    }

    /** 绑定电池到无人机（换电/热插拔）。body: {"batteryAssetId":123,"cycles":0}。 */
    @RequirePermission("drone:battery:bind")
    @PostMapping("/assets/{assetId}/battery")
    public ApiResult<DroneBatteryBinding> bindBattery(@PathVariable Long assetId,
                                                      @RequestBody BindBatteryReq req) {
        return ApiResult.ok(droneBatteryService.bind(assetId, req.batteryAssetId(), req.cycles()));
    }

    /** 解绑当前生效电池。 */
    @RequirePermission("drone:battery:bind")
    @DeleteMapping("/assets/{assetId}/battery")
    public ApiResult<Map<String, Object>> unbindBattery(@PathVariable Long assetId) {
        boolean unbound = droneBatteryService.unbind(assetId);
        Map<String, Object> result = new HashMap<>();
        result.put("unbound", unbound);
        return ApiResult.ok(result);
    }

    /** 电池供需视图（调度看板）。读接口按约定不加权限注解。 */
    @GetMapping("/battery/supply-demand")
    public ApiResult<Map<String, Object>> batterySupplyDemand() {
        return ApiResult.ok(droneBatteryService.supplyDemandView());
    }

    // --------------------------------------------------------- 切片 2 · 飞手运营

    /** 飞手档案列表（可按状态过滤：PENDING/ACTIVE/SUSPENDED/BANNED）。读接口按约定不加权限注解。 */
    @GetMapping("/pilots")
    public ApiResult<List<PilotProfile>> pilots(@RequestParam(required = false) String status) {
        return ApiResult.ok(pilotOpsService.list(status));
    }

    /** 提交飞手建档（落 PENDING）。body: {"userId":7,"licenseNo":"SSCA-...","kycLevel":"BASIC"}。 */
    @RequirePermission("drone:pilot:manage")
    @PostMapping("/pilots")
    public ApiResult<PilotProfile> submitPilot(@RequestBody SubmitPilotReq req) {
        return ApiResult.ok(pilotOpsService.submitProfile(req.userId(), req.licenseNo(), req.kycLevel()));
    }

    /** 审核通过飞手档案（PENDING → ACTIVE）。审核人取当前登录用户。 */
    @RequirePermission("drone:pilot:approve")
    @PostMapping("/pilots/{userId}/approve")
    public ApiResult<PilotProfile> approvePilot(@PathVariable Long userId) {
        return ApiResult.ok(pilotOpsService.approve(userId, AuthContext.currentUserId()));
    }

    /** 飞手行为事件（按发生时间倒序）。读接口按约定不加权限注解。 */
    @GetMapping("/pilots/{userId}/behaviors")
    public ApiResult<List<PilotBehaviorEvent>> pilotBehaviors(@PathVariable Long userId) {
        return ApiResult.ok(pilotOpsService.behaviors(userId));
    }

    /** 飞手处罚历史（按决策时间倒序）。读接口按约定不加权限注解。 */
    @GetMapping("/pilots/{userId}/penalties")
    public ApiResult<List<PilotPenalty>> pilotPenalties(@PathVariable Long userId) {
        return ApiResult.ok(pilotOpsService.penalties(userId));
    }

    /** 施加违规处罚。决策人取当前登录用户。body: {"penaltyType":"WARN","cause":"GEOFENCE_HIT",...}。 */
    @RequirePermission("drone:pilot:penalize")
    @PostMapping("/pilots/{userId}/penalties")
    public ApiResult<PilotPenalty> penalizePilot(@PathVariable Long userId,
                                                 @RequestBody PenalizeReq req) {
        return ApiResult.ok(pilotOpsService.penalize(
                userId, req.penaltyType(), req.cause(), req.severity(),
                req.points(), req.bizRef(), AuthContext.currentUserId()));
    }

    // ------------------------------------------------------------- 切片 2 · 容量

    /**
     * 无人机容量产品（capacity_type=PARALLEL 的 OPEN 计划）。
     *
     * <p>无人机产能在设计上属「并行额度」（{@link CapacityType#PARALLEL}），与车辆串行产能区分，
     * 故此处只回 PARALLEL 计划；种子见 V142。读接口按约定不加权限注解。
     */
    @GetMapping("/capacity-plans")
    public ApiResult<List<CapacityPlan>> capacityPlans() {
        List<CapacityPlan> plans = capacityPlanRepository
                .findByStatusAndDeletedFalse(CapacityPlanStatus.OPEN)
                .stream()
                .filter(p -> p.getCapacityType() == CapacityType.PARALLEL)
                .collect(Collectors.toList());
        return ApiResult.ok(plans);
    }

    // ------------------------------------------------------------------ 请求体

    /** 远控指令请求体。 */
    public record CommandReq(String command, Map<String, Object> params) {
    }

    /** 电池绑定请求体。 */
    public record BindBatteryReq(Long batteryAssetId, Integer cycles) {
    }

    /** 飞手建档请求体。 */
    public record SubmitPilotReq(Long userId, String licenseNo, String kycLevel) {
    }

    /** 飞手处罚请求体。 */
    public record PenalizeReq(String penaltyType, String cause, String severity,
                              Integer points, String bizRef) {
    }
}
