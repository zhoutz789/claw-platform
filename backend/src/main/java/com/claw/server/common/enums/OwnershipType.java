package com.claw.server.common.enums;

/**
 * 资产产权类型（对应 V12 asset_ownership.ownership_type）。
 */
public enum OwnershipType {
    /** 自用（不入池）。 */
    SELF_USE,
    /** 入共享池（其他用户可租用）。 */
    SHARED
}
