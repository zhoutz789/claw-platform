package com.claw.server.common.enums;

/** 角色包授予来源（对应 user_role_packages.source）。 */
public enum RoleSource {
    APPLY,  // 用户申请开通
    AUTO,   // 规则引擎自动授予（行为触发）
    ADMIN   // 管理员/平台人工授予
}
