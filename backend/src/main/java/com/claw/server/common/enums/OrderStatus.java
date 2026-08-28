package com.claw.server.common.enums;

/**
 * 客户订单状态机（对应 customer_orders.status）。
 *
 * <p>主链路：CREATED →(pay) PAID →(choose-mode 可选) PAID →(ship) SHIPPED →(complete) COMPLETED。
 * 任意态 →(cancel，限 CREATED/PAID) CANCELLED；PAID 后 →(refund) REFUNDED。
 * CERTIFICATED 为支付后出证的可选中间标记（GET /certificate 成功即置，不影响主链路）。
 */
public enum OrderStatus {
    CREATED,
    PAID,
    CERTIFICATED,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    REFUNDED
}
