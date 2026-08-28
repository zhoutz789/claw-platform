package com.claw.server.common.enums;

/** 资产生命周期阶段（生产/流通/溯源/回收/销毁 等）。对应 asset_lifecycle_events.stage。 */
public enum AssetLifecycleStage {
    PRODUCED,    // 生产出厂
    IN_TRANSIT,  // 流通在途
    IN_USE,      // 使用中
    MAINTENANCE, // 维修保养
    RETIRED,     // 退役（结束服务）
    RECYCLED,    // 回收（梯次利用/残值返还）
    DESTROYED    // 销毁
}
