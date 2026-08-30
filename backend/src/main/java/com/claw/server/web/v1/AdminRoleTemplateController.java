package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.role.RoleTemplate;
import com.claw.server.domain.role.RoleTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/** 角色模板管理（增量 A）。模板展开为权限集合，授予时回写 roles.grants。 */
@RestController
@RequestMapping("/api/v1/admin/role-templates")
@RequiredArgsConstructor
public class AdminRoleTemplateController {

    private final RoleTemplateService templateService;

    @GetMapping
    public ApiResult<List<RoleTemplate>> list() {
        return ApiResult.ok(templateService.listTemplates());
    }

    @GetMapping("/{code}")
    public ApiResult<RoleTemplate> get(@PathVariable String code) {
        return ApiResult.ok(templateService.getTemplate(code));
    }

    @PostMapping
    @RequirePermission("role:template:manage")
    public ApiResult<RoleTemplate> create(@RequestBody UpsertTemplate req) {
        return ApiResult.ok(templateService.createTemplate(req.code(), req.name(), req.principalType(), req.description()));
    }

    @PutMapping("/{code}")
    @RequirePermission("role:template:manage")
    public ApiResult<RoleTemplate> update(@PathVariable String code, @RequestBody UpsertTemplate req) {
        return ApiResult.ok(templateService.updateTemplate(code, req.name(), req.description()));
    }

    @GetMapping("/{code}/permissions")
    public ApiResult<List<String>> permissions(@PathVariable String code) {
        return ApiResult.ok(templateService.getPermissions(code));
    }

    @PutMapping("/{code}/permissions")
    @RequirePermission("role:template:manage")
    public ApiResult<Void> setPermissions(@PathVariable String code, @RequestBody SetPermissions req) {
        templateService.setPermissions(code, req.permissionCodes());
        return ApiResult.ok();
    }

    /**
     * 一键授予（PRD A2 / 设计 §4.3）：把模板展开的权限集合回写同名角色，并授予目标账号该角色包。
     * 幂等：重复授予不会重复建包，仅刷新角色 grants 与权限缓存。
     */
    @PostMapping("/grant")
    @RequirePermission("role:template:manage")
    public ApiResult<GrantResp> grant(@RequestBody GrantReq req) {
        Set<String> codes = templateService.grantTemplate(req.userId(), req.templateCode());
        return ApiResult.ok(new GrantResp(req.userId(), req.templateCode(), codes));
    }

    public record UpsertTemplate(String code, String name, String principalType, String description) {
    }

    public record SetPermissions(List<String> permissionCodes) {
    }

    public record GrantReq(Long userId, String templateCode) {
    }

    public record GrantResp(Long userId, String templateCode, Set<String> permissionCodes) {
    }
}
