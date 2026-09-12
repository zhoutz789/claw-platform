package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.domain.autonomy.AutonomyGeofenceService;
import com.claw.server.domain.autonomy.GroundGeofence;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 地面围栏约束端口（T8 / AU4）。
 *
 * <p>路径校验失败服务抛裸 {@code IllegalArgumentException}，按消息内容映射为语义化业务码
 * （均 → HTTP 409 状态冲突）：
 * <ul>
 *   <li>40970 → 无可用作业围栏；</li>
 *   <li>40971 → 路径进入禁行区；</li>
 *   <li>40972 → 路径超出作业区；</li>
 *   <li>40973 → 路径越界。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/vehicles/geofences")
@RequiredArgsConstructor
public class VehicleGeofenceController {

    private final AutonomyGeofenceService geofenceService;

    /** 列出全部地面围栏。 */
    @GetMapping
    public ApiResult<List<GroundGeofence>> listGeofences() {
        return ApiResult.ok(geofenceService.listGeofences());
    }

    /**
     * 校验整条路径是否全部落在作业区内且未进入禁行区。
     *
     * @throws BizException 40970/40971/40972/40973（按越界原因，均 HTTP 409）
     */
    @PostMapping("/validate")
    public ApiResult<Boolean> validatePath(@RequestBody ValidatePath req) {
        try {
            boolean ok = geofenceService.validatePathInGeofence(req.assetId(), req.pathJson());
            return ApiResult.ok(ok);
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage();
            if (msg != null) {
                // 注意：服务对越界路径统一包成 "geofence.path.out.of.bounds:<innerReason>"，
                // 其中 innerReason 可能是 no.work.zone / no.go / outside.work 之一。
                // 因此必须先匹配最外层的 path.out.of.bounds（→40973），再依次匹配内部原因，
                // 否则内部原因子串会先命中导致映射码错误。
                if (msg.contains("geofence.path.out.of.bounds")) {
                    throw BizException.of(40973, "error.vehicle.geofence.pathOutOfBounds", req.assetId());
                }
                if (msg.contains("geofence.no.work.zone")) {
                    throw BizException.of(40970, "error.vehicle.geofence.noWorkZone", req.assetId());
                }
                if (msg.contains("geofence.no.go")) {
                    throw BizException.of(40971, "error.vehicle.geofence.noGo", req.assetId());
                }
                if (msg.contains("geofence.outside.work")) {
                    throw BizException.of(40972, "error.vehicle.geofence.outsideWork", req.assetId());
                }
            }
            throw ex;
        }
    }

    public record ValidatePath(Long assetId, String pathJson) {
    }
}
