package com.claw.server.common.enums;

/** 角色数据范围。对应 roles.data_scope。SELF=仅本人数据；DEPARTMENT=本部门；ALL=全部；TYPE=特殊授权可见类型。 */
public enum DataScope {
    SELF,
    DEPARTMENT,
    ALL,
    TYPE
}
