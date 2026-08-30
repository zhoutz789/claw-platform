package com.claw.server.common.enums;

/** 设备回收单状态（R8）。PENDING→CONFIRMED→MARKED→RETURNED；CANCELLED。 */
public enum RecoveryStatus {
    PENDING,
    CONFIRMED,
    MARKED,
    RETURNED,
    CANCELLED
}
