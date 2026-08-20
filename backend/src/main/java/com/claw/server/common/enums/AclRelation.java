package com.claw.server.common.enums;

/** 资产 ACL 关系（对应 user_assets_acl.relation）。 */
public enum AclRelation {
    MANAGE, // 管理
    USE,    // 使用
    LEASE   // 承租
}
