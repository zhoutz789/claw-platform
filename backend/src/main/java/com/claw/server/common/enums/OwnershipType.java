package com.claw.server.common.enums;

/**
 * 资产产权类型（对应 V12 asset_ownership.ownership_type）。
 *
 * <p>商业模式 v2 以「全款购买」为唯一获取方式，故 FULL 为平台默认产权类型；
 * SELF_USE / SHARED 保留用于标记资产当前使用模式（自用 / 已入共享池），
 * 实际是否入池以 shared_pool_entry 是否存在为准。
 */
public enum OwnershipType {
    /** 全款购买持有（v2 默认产权类型）。 */
    FULL,
    /** 自用（不入池）。 */
    SELF_USE,
    /** 入共享池（其他用户可租用）。 */
    SHARED
}
