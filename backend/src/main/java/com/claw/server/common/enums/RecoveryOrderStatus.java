package com.claw.server.common.enums;

/**
 * 回收订单状态（对应 V13 recovery_orders.status）。
 */
public enum RecoveryOrderStatus {
    CREATED,
    VALUATION_PENDING,
    VALUATION_DONE,
    OWNER_CONFIRMED,
    PROCESSING,
    COMPLETED,
    CANCELLED,
    FAILED
}
