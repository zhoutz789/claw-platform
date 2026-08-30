package com.claw.server.common.enums;

/** 结算单状态（fulfillment_settlements）。PENDING=待结算；SETTLED=已结算；DONE=已完成；MANUAL=挂起待人工；FAILED=失败。 */
public enum SettlementStatus {
    PENDING,
    SETTLED,
    DONE,
    MANUAL,
    FAILED
}
