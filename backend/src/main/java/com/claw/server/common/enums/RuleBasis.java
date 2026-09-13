package com.claw.server.common.enums;

/**
 * 分账规则计价基准（对应 settlement_rule.basis，V129 定义）。
 */
public enum RuleBasis {
    /** 按比例（settlement_rule.rate）。 */
    RATE,
    /** 固定金额（settlement_rule.fixed_amount）。 */
    FIXED,
    /** 阶梯计价（settlement_rule.tier_json）。 */
    TIER
}
