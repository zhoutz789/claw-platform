package com.claw.server.common.enums;

/**
 * 容量定购状态（对应 V71 capacity_subscriptions.status，P1 转让/退出机制）。
 */
public enum CapacitySubscriptionStatus {
    PENDING,
    ACTIVE,
    REFUNDED,
    CANCELLED
}
