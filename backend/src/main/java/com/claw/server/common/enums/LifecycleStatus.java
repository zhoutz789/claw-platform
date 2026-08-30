package com.claw.server.common.enums;

/**
 * 设备流通链生命周期状态（R4）。
 * PRODUCING→IN_FACTORY→IN_TRANSIT→AT_STATION→SOLD→IN_USER_PROJECT；RECALLED→IN_FACTORY（回收回流）。
 * 权威历史在 lifecycle_events，库存台账 inventory.current_status 为冗余当前态。
 */
public enum LifecycleStatus {
    PRODUCING,
    IN_FACTORY,
    IN_TRANSIT,
    AT_STATION,
    SOLD,
    IN_USER_PROJECT,
    RECALLED
}
