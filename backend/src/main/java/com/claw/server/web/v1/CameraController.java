package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.CameraRequests;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.camera.Camera;
import com.claw.server.domain.camera.CameraService;
import com.claw.server.domain.camera.EventClip;
import com.claw.server.domain.camera.SparseFrame;
import com.claw.server.domain.camera.VideoSegment;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 摄像头 / 录像域：资产下相机列表、实时取流地址、历史段、时间轴、事件留证。
 * 调看受 camera:view 约束；边缘 recorder 上报段受 camera:manage 约束。
 */
@RestController
@RequestMapping("/api/v1/cameras")
@RequiredArgsConstructor
public class CameraController {

    private final CameraService cameraService;

    @RequirePermission("camera:view")
    @GetMapping
    public ApiResult<List<Camera>> list(@RequestParam Long assetId) {
        return ApiResult.ok(cameraService.listByAsset(assetId));
    }

    @RequirePermission("camera:view")
    @GetMapping("/by-asset-no/{assetNo}")
    public ApiResult<List<Camera>> listByAssetNo(@PathVariable String assetNo) {
        return ApiResult.ok(cameraService.listByAssetNo(assetNo));
    }

    @RequirePermission("camera:view")
    @GetMapping("/{id}/live")
    public ApiResult<Map<String, Object>> live(@PathVariable Long id) {
        return ApiResult.ok(cameraService.live(id));
    }

    @RequirePermission("camera:view")
    @GetMapping("/{id}/segments")
    public ApiResult<List<VideoSegment>> segments(@PathVariable Long id,
                                                 @RequestParam(required = false) Instant from,
                                                 @RequestParam(required = false) Instant to) {
        return ApiResult.ok(cameraService.segments(id, from, to));
    }

    @RequirePermission("camera:view")
    @GetMapping("/{id}/timeline")
    public ApiResult<Map<String, Object>> timeline(@PathVariable Long id) {
        return ApiResult.ok(cameraService.timeline(id));
    }

    @RequirePermission("camera:manage")
    @PostMapping("/{id}/segments")
    public ApiResult<VideoSegment> recordSegment(@PathVariable Long id,
                                                @Valid @RequestBody CameraRequests.RecordSegment req) {
        return ApiResult.ok(cameraService.recordSegment(id, req));
    }
}
