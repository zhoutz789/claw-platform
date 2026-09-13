package com.claw.server.common.enums;

/**
 * 分账规则计价基准（对应 settlement_rule.basis，V129 定义 + V132 显式化 {@link #RESIDUAL}）。
 */
public enum RuleBasis {
    /** 按比例（settlement_rule.rate）。 */
    RATE,
    /** 固定金额（settlement_rule.fixed_amount）。 */
    FIXED,
    /** 阶梯计价（settlement_rule.tier_json）。 */
    TIER,
    /**
     * 残差兜底（V132 显式化）：该腿金额 = 交易总额 − Σ其余腿，<b>无需</b> rate / fixed_amount / tier_json。
     *
     * <p>同一 {@code biz_scene} 内筛选后应当只有一条 RESIDUAL 规则（多条时取 {@code priority} 最大者作为兜底，
     * 其余 RESIDUAL 规则会被 {@code SplitEngine} 判为配置错误并抛 {@code error.clearing.rule.invalid}，
     * 绝不按 0 或全额静默错账）。
     *
     * <p>通道硬约束：分账总额必须精确等于交易总额（ABA 错误码 92），故每腿金额在提交通道前精确到分，
     * 产生的尾差由平台收入（{@code PLATFORM_REVENUE}）吸收。
     */
    RESIDUAL
}
