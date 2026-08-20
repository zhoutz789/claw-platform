package com.claw.server.common.enums;

/**
 * 支付单状态（对应 claw.payment_orders.status，V1 定义）。
 * 状态机：CREATED → PAID / FAILED；PAID → REFUNDED（退款）。
 */
public enum PayStatus {
    /** 已创建（收单待支付）。 */
    CREATED,
    /** 已支付（回调对账后入账）。 */
    PAID,
    /** 支付失败。 */
    FAILED,
    /** 已退款。 */
    REFUNDED
}
