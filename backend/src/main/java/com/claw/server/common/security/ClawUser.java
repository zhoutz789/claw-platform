package com.claw.server.common.security;

/**
 * 登录态主体：从 JWT 解析后注入 Spring Security 上下文。
 * 仅含轻量标识，权限在需要处经 PermissionService 实时计算。
 */
public record ClawUser(Long userId, String phone, String roleSummary) {
    public static final String AUTHORITY_USER = "ROLE_USER";
}
