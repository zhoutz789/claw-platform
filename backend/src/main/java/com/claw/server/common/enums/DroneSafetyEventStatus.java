package com.claw.server.common.enums;

/** 安全事件处置状态（区别于资产安全态 DroneSafetyStatus）：未解除 / 已解除。 */
public enum DroneSafetyEventStatus {
    OPEN,     // 未解除（锁机中）
    RESOLVED  // 已解除
}
