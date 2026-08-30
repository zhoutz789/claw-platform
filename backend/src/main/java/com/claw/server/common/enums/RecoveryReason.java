package com.claw.server.common.enums;

/** 设备回收原因（device_recovery_orders）。 */
public enum RecoveryReason {
    UNSOLD_TIMEOUT,   // 入寄售库超期未成交（Q1 起算 inbound_at）
    FULFILL_TIMEOUT,  // 待履约订单履约超时
    MANUAL            // 厂家手动发起
}
