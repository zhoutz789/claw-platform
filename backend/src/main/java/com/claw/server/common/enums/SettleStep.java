package com.claw.server.common.enums;

/**
 * 结算顺序（R7，常量见 §7）：取货扫码触发结算，按序（单步失败挂起，Q6）：
 * 1) LOGISTICS        —— 扣物流费（厂家承担，走 ledger）
 * 2) COMMISSION       —— 扣服务站提成（按 device_sales_commission_rules，走 settlement）
 * 3) BALANCE_TO_MFG   —— 余额归厂家（释放冻结）
 */
public enum SettleStep {
    LOGISTICS,
    COMMISSION,
    BALANCE_TO_MFG
}
