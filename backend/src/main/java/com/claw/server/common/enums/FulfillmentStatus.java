package com.claw.server.common.enums;

/**
 * 待履约订单状态机（R6）。
 * PENDING_PAYMENT→PAID_FROZEN→CONFIRMED→SHIPPED→RECEIVED→PICKED_UP→SETTLED；
 * 任意节点 CANCELLED / EXPIRED（释放冻结，Q6 不自动全额退款）。
 */
public enum FulfillmentStatus {
    PENDING_PAYMENT,
    PAID_FROZEN,
    CONFIRMED,
    SHIPPED,
    RECEIVED,
    PICKED_UP,
    SETTLED,
    CANCELLED,
    EXPIRED
}
