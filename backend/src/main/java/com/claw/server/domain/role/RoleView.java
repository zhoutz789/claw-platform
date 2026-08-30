package com.claw.server.domain.role;

import com.claw.server.common.enums.RoleSource;

import java.time.Instant;

/**
 * 角色包视图（控制器出参）：主键 + 角色码 + i18n 名称 + 授予来源 + 授予时间 + 数据范围 + 父角色 id。
 * 含 id 以便前端 CrudTable 以 id 作为 rowKey 执行删除；parentId 供前端「父角色」继承展示/选择。
 */
public record RoleView(Long id, String roleCode, String nameI18n, RoleSource source, Instant grantedAt,
                       String dataScope, Long parentId, String dataScopeTypes, String dataRuleIds) {
    public static RoleView of(Long id, String roleCode, String nameI18n, RoleSource source, Instant grantedAt,
                              String dataScope, Long parentId, String dataScopeTypes, String dataRuleIds) {
        return new RoleView(id, roleCode, nameI18n, source, grantedAt, dataScope, parentId,
                dataScopeTypes, dataRuleIds);
    }
}
