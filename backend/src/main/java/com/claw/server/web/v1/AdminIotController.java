package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.GeofenceDtos.*;
import com.claw.server.common.dto.LocationDtos.ProductLocationView;
import com.claw.server.common.dto.LocationDtos.TrackPointView;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.iot.GeofenceService;
import com.claw.server.domain.iot.LocationViewService;
import com.claw.server.domain.role.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import com.claw.server.common.security.RequirePermission;

/**
 * 后台 IoT 位置/围栏（Increment 3 A 期）。
 *
 * <p>端点（前缀 /api/v1/iot）：
 * <ul>
 *   <li>GET /locations/product/{productId}        产品聚合位置（派生，读）</li>
 *   <li>GET /tracks/{assetId}?from=&to=           历史轨迹回放（读）</li>
 *   <li>GET /geofences?ownerType=&ownerId=        围栏列表（读）</li>
 *   <li>POST /geofences                           创建围栏（写，限管理员）</li>
 *   <li>GET /geofences/{id}                       围栏详情（读）</li>
 *   <li>PUT /geofences/{id}                       更新围栏（写，限管理员）</li>
 *   <li>DELETE /geofences/{id}                    删除围栏（写，限管理员）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/iot")
@RequiredArgsConstructor
public class AdminIotController {

    private final LocationViewService locationViewService;
    private final GeofenceService geofenceService;
    private final PermissionService permissionService;

    private void requireAdmin() {
        Long uid = AuthContext.currentUserId();
        if (uid == null || !permissionService.isPlatformAdmin(uid)) {
            throw BizException.of(40300, "error.forbidden");
        }
    }

    @GetMapping("/locations/product/{productId}")
    public ApiResult<ProductLocationView> productLocation(@PathVariable Long productId) {
        return ApiResult.ok(locationViewService.getProductLocation(productId));
    }

    @GetMapping("/tracks/{assetId}")
    public ApiResult<List<TrackPointView>> track(@PathVariable Long assetId,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ApiResult.ok(locationViewService.getTrack(assetId, from, to));
    }

    @GetMapping("/geofences")
    public ApiResult<List<GeofenceView>> listGeofences(@RequestParam String ownerType, @RequestParam Long ownerId) {
        return ApiResult.ok(geofenceService.listByOwner(ownerType, ownerId));
    }

    @PostMapping("/geofences")
    @RequirePermission("api:create")
    public ApiResult<GeofenceView> createGeofence(@RequestBody CreateGeofenceReq req) {
        requireAdmin();
        return ApiResult.ok(geofenceService.create(req));
    }

    @GetMapping("/geofences/{id}")
    public ApiResult<GeofenceView> getGeofence(@PathVariable Long id) {
        return ApiResult.ok(geofenceService.get(id));
    }

    @PutMapping("/geofences/{id}")
    @RequirePermission("api:update")
    public ApiResult<GeofenceView> updateGeofence(@PathVariable Long id, @RequestBody UpdateGeofenceReq req) {
        requireAdmin();
        return ApiResult.ok(geofenceService.update(id, req));
    }

    @DeleteMapping("/geofences/{id}")
    @RequirePermission("api:delete")
    public ApiResult<Void> deleteGeofence(@PathVariable Long id) {
        requireAdmin();
        geofenceService.delete(id);
        return ApiResult.ok();
    }
}
