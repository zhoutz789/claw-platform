package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTViews;
import com.claw.server.common.enums.DroneCommandType;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.asset.DroneCommandService;
import com.claw.server.domain.asset.DroneProductClass;
import com.claw.server.domain.asset.DroneProductClassService;
import com.claw.server.domain.iot.DroneTrajectory;
import com.claw.server.domain.iot.DroneTrajectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 无人机作业管控（切片 1）：机型配置查询 + 航迹回放 + 远控指令下发。
 *
 * <p>端点（前缀 /api/v1/admin/drone）：
 * <ul>
 *   <li>GET  /product-classes                             机型产品类列表（读）</li>
 *   <li>GET  /assets/{assetId}/trajectory?from=&to=      航迹回放（读）</li>
 *   <li>POST /assets/{assetId}/command                   远控指令下发（写）</li>
 * </ul>
 * 远控经 {@link DroneCommandService} → {@code DeviceCommandService}（HMAC 签名 + 审计落库 + 下发），
 * 复用既有 {@code device_commands}，不另造指令日志表。权限位由 V139 播种（三步法）。
 */
@RestController
@RequestMapping("/api/v1/admin/drone")
@RequiredArgsConstructor
public class DroneOpsController {

    private final DroneProductClassService droneProductClassService;
    private final DroneTrajectoryService droneTrajectoryService;
    private final DroneCommandService droneCommandService;

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

    /** 远控指令请求体。 */
    public record CommandReq(String command, Map<String, Object> params) {
    }
}
