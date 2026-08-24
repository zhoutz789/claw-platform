package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.AdminDtos.*;
import com.claw.server.common.enums.RoleSource;
import com.claw.server.domain.role.Role;
import com.claw.server.domain.role.RoleRepository;
import com.claw.server.domain.role.RoleView;
import com.claw.server.domain.role.UserRolePackage;
import com.claw.server.domain.role.UserRolePackageRepository;
import com.claw.server.domain.user.User;
import com.claw.server.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 后台角色权限模块（S5 补齐）：角色目录维护 + 用户角色包授予/回收。
 *
 * <p>GET    /roles              角色目录列表
 * POST   /roles              新增角色
 * PUT    /roles/{id}         修改角色
 * DELETE /roles/{id}         停用角色（置 INACTIVE）
 * GET    /roles/user-roles   用户角色包授予列表
 * POST   /roles/user-roles   授予用户角色包（admin 来源）
 * DELETE /roles/user-roles/{id}  回收角色包
 */
@RestController
@RequestMapping("/api/v1/admin/roles")
@RequiredArgsConstructor
public class AdminRoleController {

    private final RoleRepository roleRepository;
    private final UserRolePackageRepository packageRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ApiResult<List<RoleView>> listRoles() {
        return ApiResult.ok(roleRepository.findAll().stream()
                .map(r -> RoleView.of(r.getId(), r.getCode(), r.getNameI18n(), null, null, r.getDataScope()))
                .toList());
    }

    @PostMapping
    public ApiResult<RoleView> createRole(@RequestBody RoleReq req) {
        if (roleRepository.findByCode(req.code()).isPresent()) {
            throw new BizException(40901, "role.code.exists");
        }
        Role r = Role.builder()
                .code(req.code())
                .nameI18n(req.nameI18n())
                .grants(req.grants() == null || req.grants().isBlank() ? "{}" : req.grants())
                .autoGrant(Boolean.TRUE.equals(req.autoGrant()))
                .grantRule(req.grantRule())
                .status(req.status() == null ? "ACTIVE" : req.status())
                .dataScope(req.dataScope() == null ? "SELF" : req.dataScope())
                .dataScopeTypes(req.dataScopeTypes() == null ? "[]" : req.dataScopeTypes())
                .build();
        r = roleRepository.save(r);
        return ApiResult.ok(RoleView.of(r.getId(), r.getCode(), r.getNameI18n(), null, null, r.getDataScope()));
    }

    @PutMapping("/{id}")
    public ApiResult<RoleView> updateRole(@PathVariable Long id, @RequestBody RoleReq req) {
        Role r = roleRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "role.not.found"));
        if (req.nameI18n() != null) r.setNameI18n(req.nameI18n());
        if (req.grants() != null) r.setGrants(req.grants());
        if (req.autoGrant() != null) r.setAutoGrant(req.autoGrant());
        if (req.grantRule() != null) r.setGrantRule(req.grantRule());
        if (req.status() != null) r.setStatus(req.status());
        if (req.dataScope() != null) r.setDataScope(req.dataScope());
        if (req.dataScopeTypes() != null) r.setDataScopeTypes(req.dataScopeTypes());
        r = roleRepository.save(r);
        return ApiResult.ok(RoleView.of(r.getId(), r.getCode(), r.getNameI18n(), null, null, r.getDataScope()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> deleteRole(@PathVariable Long id) {
        Role r = roleRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "role.not.found"));
        r.setStatus("INACTIVE");
        roleRepository.save(r);
        return ApiResult.ok();
    }

    @GetMapping("/user-roles")
    public ApiResult<List<UserRoleAssignmentView>> listUserRoles() {
        Map<Long, User> users = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Role> roles = roleRepository.findAll().stream()
                .collect(Collectors.toMap(Role::getId, Function.identity()));
        return ApiResult.ok(packageRepository.findAll().stream().map(p -> {
            User u = users.get(p.getUserId());
            Role role = roles.get(p.getRoleId());
            return new UserRoleAssignmentView(p.getId(), p.getUserId(),
                    u == null ? String.valueOf(p.getUserId()) : (u.getPhone() != null ? u.getPhone() : u.getFullName()),
                    role == null ? null : role.getCode(),
                    role == null ? null : role.getNameI18n(),
                    p.getSource() == null ? null : p.getSource().name(),
                    p.getGrantedAt(), p.getRevokedAt());
        }).toList());
    }

    @PostMapping("/user-roles")
    public ApiResult<UserRoleAssignmentView> assignRole(@RequestBody UserRoleAssignReq req) {
        Role role = roleRepository.findByCode(req.roleCode())
                .orElseThrow(() -> new BizException(40401, "role.not.found"));
        UserRolePackage pkg = packageRepository.findByUserIdAndRoleId(req.userId(), role.getId())
                .map(p -> {
                    p.setRevokedAt(null);
                    p.setSource(RoleSource.ADMIN);
                    p.setGrantedAt(Instant.now());
                    return packageRepository.save(p);
                })
                .orElseGet(() -> packageRepository.save(UserRolePackage.builder()
                        .userId(req.userId())
                        .roleId(role.getId())
                        .source(RoleSource.ADMIN)
                        .grantedAt(Instant.now())
                        .build()));
        User u = userRepository.findById(req.userId()).orElse(null);
        return ApiResult.ok(new UserRoleAssignmentView(pkg.getId(), pkg.getUserId(),
                u == null ? null : u.getPhone(), role.getCode(), role.getNameI18n(),
                pkg.getSource().name(), pkg.getGrantedAt(), pkg.getRevokedAt()));
    }

    @DeleteMapping("/user-roles/{id}")
    public ApiResult<Void> revokeRole(@PathVariable Long id) {
        UserRolePackage pkg = packageRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "role.package.not.found"));
        pkg.setRevokedAt(Instant.now());
        packageRepository.save(pkg);
        return ApiResult.ok();
    }
}
