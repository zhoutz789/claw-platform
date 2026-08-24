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
    ESCROW_COLLECT,
    /** 共享池租赁结算（V12：所有人/站点/平台/保险分账）。 */
    RENTAL_SETTLEMENT,
    /** 资产全款购买（修改1：取消公众募资，全款购买）。 */
    ASSET_PURCHASE,
    /** 残值回收入账（V13：回收资金到用户账户）。 */
    RECOVERY_PAYOUT,
    /** 保险理赔赔付（V15：保险基金 → 用户账户）。 */
    INSURANCE_CLAIM,
    /** 保险基金计提（V12：每笔交易 5% 保险分成入基金）。 */
    INSURANCE_FUND_ACCRUAL,
    /** 站方收益结算（V10：管理收益/服务费/光伏收益到站方账户）。 */
    OPERATOR_REVENUE
}
