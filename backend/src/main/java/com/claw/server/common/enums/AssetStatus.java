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
    RETIRED,    // 退役（结束服务，待回收/残值评估）
    RECYCLED,   // 回收（梯次利用/残值返还流程中）
    SCRAPPED,   // 报废（销毁，终态）
    LISTED      // 资产大厅·公开可见（V36 转让至资产大厅）
}
