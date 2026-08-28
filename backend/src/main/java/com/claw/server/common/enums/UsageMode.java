package com.claw.server.common.enums;

/**
 * 客户订单资产使用模式（对应 customer_orders.usage_mode）。
 * SELF = 自用（不入池）；SHARED = 入共享池（其他用户可租用）。
 */
public enum UsageMode {
    SELF,
    SHARED
}
