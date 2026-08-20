package com.claw.server.domain.role;

import com.claw.server.common.enums.RoleSource;

import java.time.Instant;

/**
 * 角色包视图（控制器出参）：角色码 + i18n 名称 + 授予来源 + 授予时间。
 */
public record RoleView(String roleCode, String nameI18n, RoleSource source, Instant grantedAt) {
    public static RoleView of(String roleCode, String nameI18n, RoleSource source, Instant grantedAt) {
        return new RoleView(roleCode, nameI18n, source, grantedAt);
    }
}
