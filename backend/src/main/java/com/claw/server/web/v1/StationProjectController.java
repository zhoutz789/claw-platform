package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.station.StationProjectService;
import com.claw.server.domain.station.StationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 服务站项目层端点（模块四 · ②，前缀 /api/v1/station/projects）。
 *
 * <p>读接口按 {@link com.claw.server.domain.station.StationScopeService#allowedStationIds} 过滤（BC-5）。
 */
@RestController
@RequestMapping("/api/v1/station/projects")
@RequiredArgsConstructor
public class StationProjectController {

    private final StationProjectService projectService;
    private final StationScopeService scopeService;

    @GetMapping
    @RequirePermission({"station:project:view", "mfg:station:project:view"})
    public ApiResult<List<StationViews.StationProjectTreeNode>> list() {
        return ApiResult.ok(projectService.listTree(scopeService.allowedStationIds(null)));
    }

    @PostMapping
    @RequirePermission("station:project:manage")
    public ApiResult<StationViews.StationProjectView> create(@RequestBody StationRequests.StationProjectCreate req) {
        return ApiResult.ok(projectService.create(req, AuthContext.currentUserId()));
    }

    @PutMapping("/{id}")
    @RequirePermission("station:project:manage")
    public ApiResult<StationViews.StationProjectView> update(@PathVariable Long id,
                                                             @RequestBody StationRequests.StationProjectUpdate req) {
        return ApiResult.ok(projectService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("station:project:manage")
    public ApiResult<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return ApiResult.ok();
    }

    @GetMapping("/{id}/allocations")
    @RequirePermission({"station:project:view", "mfg:station:project:view"})
    public ApiResult<List<StationViews.StationProjectAllocView>> allocations(@PathVariable Long id) {
        return ApiResult.ok(projectService.listAllocs(id));
    }

    @PostMapping("/{id}/allocations")
    @RequirePermission("station:project:alloc")
    public ApiResult<StationViews.StationProjectAllocView> alloc(@PathVariable Long id,
                                                                 @RequestBody StationRequests.StationProjectAlloc req) {
        return ApiResult.ok(projectService.alloc(id, req));
    }

    @DeleteMapping("/allocations/{allocId}")
    @RequirePermission("station:project:alloc")
    public ApiResult<Void> dealloc(@PathVariable Long allocId) {
        projectService.dealloc(allocId);
        return ApiResult.ok();
    }
}
