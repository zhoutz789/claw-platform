package com.claw.server.web.support;

import com.claw.server.common.api.BizException;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.role.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 权限校验切面（权限通电内核）：在标注 {@link RequirePermission} 的 admin 写/导出/下载接口执行前，
 * 校验当前登录用户是否持有所需权限位。
 *
 * <p>设计要点：
 * <ul>
 *   <li>本切面位于 web.support 包（参考既有 web.support 约定），依赖 common.security（注解）与
 *       domain.role.PermissionService（权限聚合），不违反 ArchUnit「common 不得依赖 domain」边界。</li>
 *   <li>开发态 {@code claw.security.dev-open-access=true} 时直接放行（与 SecurityConfig「放开全部接口」一致），
 *       便于本地直连真实库体验全链路，不依赖前端演示 token。</li>
 *   <li>权限集合来自 {@link PermissionService#effectivePermissions(Long)}（含 Redis 缓存 + 降级直查 DB），
 *       命中注解任一权限位或通配符 {@code "*"} 即放行，否则抛 {@code 40301}（HTTP 403）。</li>
 * </ul>
 */
@Aspect
@Component
@Order(1)
@Slf4j
@RequiredArgsConstructor
public class PermissionAspect {

    private final PermissionService permissionService;

    @Value("${claw.security.dev-open-access:false}")
    private boolean devOpenAccess;

    @Before("@annotation(com.claw.server.common.security.RequirePermission) && @annotation(ann)")
    public void check(JoinPoint joinPoint, RequirePermission ann) {
        // 开发态放开全部接口：跳过鉴权，仅做登录态校验（SecurityConfig 已保证 authenticated）。
        if (devOpenAccess) {
            return;
        }

        Long userId = AuthContext.currentUserId();
        if (userId == null) {
            // 理论上 SecurityConfig 已拦截未登录，这里兜底。
            throw BizException.of(40301, "error.permission.denied");
        }

        Set<String> granted = permissionService.effectivePermissions(userId);
        for (String needed : ann.value()) {
            if (granted.contains(needed) || granted.contains("*")) {
                return;
            }
        }
        log.warn("权限不足：userId={} 需要 {} 实际={}", userId, ann.value(), granted);
        throw BizException.of(40301, "error.permission.denied");
    }
}
