package com.claw.server.common.enums;

/**
 * 租赁订单状态（对应 V12 rental_orders.status）。
 */
public enum RentalOrderStatus {
    CREATED,
    ACTIVE,
    COMPLETED,
    CANCELLED,
    DISPUTED
}
