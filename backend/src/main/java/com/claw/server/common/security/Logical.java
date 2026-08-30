package com.claw.server.common.security;

/**
 * 数据范围多条件组合逻辑（占位，供 {@link DataScope} 使用）。
 * 当前数据范围以单一最宽松 scope 生效，logical 预留以便后续支持多实体 AND/OR 组合。
 */
public enum Logical {
    AND,
    OR
}
