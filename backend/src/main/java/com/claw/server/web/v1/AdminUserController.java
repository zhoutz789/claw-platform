package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.enums.RoleSource;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.DataScope;
import com.claw.server.common.security.DataScopeContext;
import com.claw.server.common.security.DataScopeFieldMapping;
import com.claw.server.common.security.DataScopeResult;
import com.claw.server.common.security.DataScopeSpec;
import com.claw.server.domain.role.*;
import com.claw.server.domain.user.Department;
import com.claw.server.domain.user.User;
import com.claw.server.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import com.claw.server.common.security.RequirePermission;

/**
 * 后台用户管理（S5）：列出平台用户及其角色包，并支持给用户增删改角色。
 * 仅需登录即可访问（与 admin/dashboard 同口径）。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;
    private final RoleGrantService roleGrantService;
    private final RoleRepository roleRepository;
    private final UserRolePackageRepository packageRepository;
    private final DataScopeService dataScopeService;
    private final PermissionService permissionService;

    @DataScope(entity = "user")
    @GetMapping("/users")
    public ApiResult<List<ApiViews.AdminUserView>> listUsers() {
        // 数据范围 enforcement（P1-T03）：DataScopeAspect 写入 DataScopeContext；
        // 非经切面进入时降级直接解析，保证过滤不丢。
        DataScopeResult ds = DataScopeContext.get();
        if (ds == null) {
            ds = dataScopeService.resolve(AuthContext.currentUserId());
        }
        Specification<User> spec = (ds == null || ds.isAll())
                ? (root, q, cb) -> cb.conjunction()
                : DataScopeSpec.of(DataScopeFieldMapping.of("id", "departmentId", null, null,
                        User.class, Department.class)).apply(ds);
        List<User> users = userRepository.findAll(spec);
        List<ApiViews.AdminUserView> views = users.stream().map(this::toView).toList();
        return ApiResult.ok(views);
    }

    /** 给用户设置所属部门（数据范围 DEPARTMENT/TYPE 维度锚点）。 */
    @Transactional
    @PutMapping("/users/{id}/department")
    @RequirePermission("user:update")
    public ApiResult<Void> setUserDepartment(@PathVariable Long id, @RequestBody UserDepartmentReq req) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(40401, "user.not.found"));
        u.setDepartmentId(req.departmentId());
        userRepository.save(u);
        return ApiResult.ok();
    }

    /** 整体替换某用户的角色包（分配/调整角色）。 */
    @Transactional
    @PutMapping("/users/{id}/roles")
    @RequirePermission("user:update")
    public ApiResult<List<String>> setUserRoles(@PathVariable Long id, @RequestBody UserRolesReq req) {
        User u = userRepository.findById(id).orElseThrow(() -> new BizException(40401, "user.not.found"));
        // 撤销全部现有生效包
        packageRepository.findByUserId(id).forEach(p -> {
            p.setRevokedAt(Instant.now());
            packageRepository.save(p);
        });
        // 授予新集合
        for (String code : req.roleCodes()) {
            Role role = roleRepository.findByCode(code)
                    .orElseThrow(() -> new BizException(40401, "role.not.found:" + code));
            UserRolePackage pkg = packageRepository.findByUserIdAndRoleId(id, role.getId())
                    .map(p -> { p.setRevokedAt(null); p.setSource(RoleSource.ADMIN); p.setGrantedAt(Instant.now()); return packageRepository.save(p); })
                    .orElseGet(() -> packageRepository.save(UserRolePackage.builder()
                            .userId(id).roleId(role.getId()).source(RoleSource.ADMIN).grantedAt(Instant.now()).build()));
        }
        permissionService.evictUser(id);
        return ApiResult.ok(roleGrantService.listActive(id).stream().map(RoleView::roleCode).toList());
    }

    /** 追加单个角色。 */
    @Transactional
    @PostMapping("/users/{id}/roles")
    @RequirePermission("user:create")
    public ApiResult<List<String>> addUserRole(@PathVariable Long id, @RequestBody UserRoleCodeReq req) {
        if (!userRepository.existsById(id)) throw new BizException(40401, "user.not.found");
        roleGrantService.apply(id, req.roleCode());
        permissionService.evictUser(id);
        return ApiResult.ok(roleGrantService.listActive(id).stream().map(RoleView::roleCode).toList());
    }

    /** 移除单个角色。 */
    @Transactional
    @DeleteMapping("/users/{id}/roles/{code}")
    @RequirePermission("user:delete")
    public ApiResult<List<String>> removeUserRole(@PathVariable Long id, @PathVariable String code) {
        Role role = roleRepository.findByCode(code).orElseThrow(() -> new BizException(40401, "error.role.not.found"));
        packageRepository.findByUserIdAndRoleId(id, role.getId()).ifPresent(p -> {
            p.setRevokedAt(Instant.now());
            packageRepository.save(p);
        });
        permissionService.evictUser(id);
        return ApiResult.ok(roleGrantService.listActive(id).stream().map(RoleView::roleCode).toList());
    }

    private ApiViews.AdminUserView toView(User u) {
        List<String> roles = roleGrantService.listActive(u.getId()).stream()
                .map(RoleView::roleCode)
                .toList();
        return new ApiViews.AdminUserView(u.getId(), u.getPhone(), u.getFullName(),
                u.getKycStatus(), u.getStatus().name(), u.getLocale(), roles, u.getDepartmentId());
    }

    public record UserRolesReq(List<String> roleCodes) {
    }

    public record UserRoleCodeReq(String roleCode) {
    }

    public record UserDepartmentReq(Long departmentId) {
    }
}
