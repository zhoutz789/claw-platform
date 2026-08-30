package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.role.RoleGroup;
import com.claw.server.domain.role.RoleGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 角色组管理（增量 A）。角色组聚合多个角色模板（系统管理第 6 项能力载体）。 */
@RestController
@RequestMapping("/api/v1/admin/role-groups")
@RequiredArgsConstructor
public class AdminRoleGroupController {

    private final RoleGroupService groupService;

    @GetMapping
    public ApiResult<List<RoleGroup>> list() {
        return ApiResult.ok(groupService.listGroups());
    }

    @GetMapping("/{code}")
    public ApiResult<RoleGroup> get(@PathVariable String code) {
        return ApiResult.ok(groupService.getGroup(code));
    }

    @PostMapping
    @RequirePermission("role:group:manage")
    public ApiResult<RoleGroup> create(@RequestBody UpsertGroup req) {
        Long op = AuthContext.currentUserId();
        return ApiResult.ok(groupService.createGroup(req.code(), req.name(), req.description(), op));
    }

    @PutMapping("/{code}")
    @RequirePermission("role:group:manage")
    public ApiResult<RoleGroup> update(@PathVariable String code, @RequestBody UpsertGroup req) {
        return ApiResult.ok(groupService.updateGroup(code, req.name(), req.description()));
    }

    @DeleteMapping("/{code}")
    @RequirePermission("role:group:manage")
    public ApiResult<Void> delete(@PathVariable String code) {
        groupService.deleteGroup(code);
        return ApiResult.ok();
    }

    @GetMapping("/{code}/templates")
    public ApiResult<List<String>> templates(@PathVariable String code) {
        return ApiResult.ok(groupService.listTemplates(code));
    }

    @PostMapping("/{code}/templates")
    @RequirePermission("role:group:manage")
    public ApiResult<Void> addTemplate(@PathVariable String code, @RequestBody AddTemplate req) {
        groupService.addTemplate(code, req.templateCode());
        return ApiResult.ok();
    }

    @DeleteMapping("/{code}/templates/{templateCode}")
    @RequirePermission("role:group:manage")
    public ApiResult<Void> removeTemplate(@PathVariable String code, @PathVariable String templateCode) {
        groupService.removeTemplate(code, templateCode);
        return ApiResult.ok();
    }

    public record UpsertGroup(String code, String name, String description) {
    }

    public record AddTemplate(String templateCode) {
    }
}
