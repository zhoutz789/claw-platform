package com.claw.server.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户上下文。
 *
 * <p>控制器/服务层直接调用 {@link #currentUser()} 取 {@link ClawUser}；
 * 未登录或上下文缺失时返回 null（由接口自身的权限注解/校验决定放行或拒绝）。
 */
public final class AuthContext {

    private AuthContext() {
    }

    public static ClawUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof ClawUser user)) {
            return null;
        }
        return user;
    }

    public static Long currentUserId() {
        ClawUser user = currentUser();
        return user == null ? null : user.userId();
    }
}
