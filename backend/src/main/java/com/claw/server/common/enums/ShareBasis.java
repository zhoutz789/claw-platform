package com.claw.server.common.enums;

/** 分润计费基础（治理层分润引擎，与 partner_programs.share_basis 一致）。 */
public enum ShareBasis {
    GMV,          // 交易额
    PROFIT,       // 利润
    SUBSCRIPTION  // 订阅额
}
