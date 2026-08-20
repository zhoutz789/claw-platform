package com.claw.server.common.enums;

/**
 * 资产状态机（对应 assets.status）。
 * 合法流转见 {@code AssetStateMachine}：在库 → 使用中 → 共享中 → 维修/停用/报废。
 */
public enum AssetStatus {
    IN_STOCK,   // 在库
    IN_USE,     // 使用中
    SHARED,     // 共享中
    REPAIR,     // 维修中
    DISABLED,   // 停用
    SCRAPPED    // 报废
}
