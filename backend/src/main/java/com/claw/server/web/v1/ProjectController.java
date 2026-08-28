package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.dto.ProjectDtos.*;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 项目管理端点（Increment 3 C 期，前缀 /api/v1/projects）。
 *
 * <p>所有写操作以当前登录用户为 owner（经 AuthContext 取 id）；沿用 A 期
 * AdminProductController 风格：ApiResult&lt;T&gt; + @RequiredArgsConstructor + @RestController。
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ApiResult<List<ProjectTreeNode>> list() {
        return ApiResult.ok(projectService.listByOwner(AuthContext.currentUserId()));
    }

    @PostMapping
    public ApiResult<ProjectView> create(@RequestBody CreateProjectReq req) {
        return ApiResult.ok(projectService.create(req, AuthContext.currentUserId()));
    }

    @PutMapping("/{id}")
    public ApiResult<ProjectView> update(@PathVariable Long id, @RequestBody UpdateProjectReq req) {
        return ApiResult.ok(projectService.update(id, req, AuthContext.currentUserId()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        projectService.delete(id, AuthContext.currentUserId());
        return ApiResult.ok();
    }

    @GetMapping("/{id}/devices")
    public ApiResult<List<ProjectDeviceView>> devices(@PathVariable Long id) {
        return ApiResult.ok(projectService.getDevices(id, AuthContext.currentUserId()));
    }

    @PostMapping("/{id}/devices")
    public ApiResult<ProjectDeviceView> bind(@PathVariable Long id, @RequestBody BindDeviceReq req) {
        return ApiResult.ok(projectService.bindDevice(id, req.assetId(), AuthContext.currentUserId()));
    }

    @DeleteMapping("/devices/{pdId}")
    public ApiResult<Void> unbind(@PathVariable Long pdId) {
        projectService.unbindDevice(pdId, AuthContext.currentUserId());
        return ApiResult.ok();
    }

    @PostMapping("/devices/{pdId}/authorize")
    public ApiResult<DeviceAuthorizationView> authorize(@PathVariable Long pdId,
                                                        @Valid @RequestBody DeviceAuthorizeReq req) {
        return ApiResult.ok(projectService.authorize(pdId, req, AuthContext.currentUserId()));
    }

    @GetMapping("/{id}/account")
    public ApiResult<ProjectAccountView> account(@PathVariable Long id) {
        return ApiResult.ok(projectService.getAccount(id, AuthContext.currentUserId()));
    }

    @PostMapping("/{id}/entries")
    public ApiResult<LedgerViews.TxnResult> recordEntry(@PathVariable Long id,
                                                        @RequestBody ProjectEntryReq req) {
        return ApiResult.ok(projectService.recordProjectEntry(
                id, req.amount(), req.type(), req.memo(), AuthContext.currentUserId()));
    }
}
