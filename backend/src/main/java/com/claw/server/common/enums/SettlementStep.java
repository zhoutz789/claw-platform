package com.claw.server.common.enums;

/**
 * 结算顺序常量（R7，§7）。取货扫码触发结算，按序：
 * LOGISTICS（物流费，厂家承担）→ COMMISSION（服务站提成）→ BALANCE（余额归厂家）。
 * 任一步失败挂起（PENDING→MANUAL/FAILED），不自动全额退款（Q6）。
 */
public enum SettlementStep {
    LOGISTICS,
    COMMISSION,
    BALANCE
}
