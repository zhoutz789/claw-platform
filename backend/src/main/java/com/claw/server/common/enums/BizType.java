package com.claw.server.common.enums;

/**
 * 记账业务类型（对应 claw.account_entries.biz_type，V1 定义）。
 * 每笔资金动作必须携带 bizType + bizRef（业务单号），构成幂等键。
 */
public enum BizType {
    /** 充值。 */
    RECHARGE,
    /** 换电结算。 */
    SWAP_PAY,
    /** 押金冻结/退回。 */
    DEPOSIT_HOLD,
    /** 基金计提（换电基金/电池基金）。 */
    FUND_ACCRUAL,
    /** 分期月付（投资者 80% / 风险准备金 10% / 平台 10% 拆分）。 */
    INSTALLMENT,
    /** 资产认购结算（投资者全款购买厂家产品）。 */
    SUBSCRIPTION,
    /** 退款。 */
    REFUND,
    /** 提现（ABA 出金，账本扣减）。 */
    WITHDRAW,
    /** 三专户收单（KHQR 收单入账到残值准备金/电池基金/车辆风险金）。 */
    ESCROW_COLLECT
}
