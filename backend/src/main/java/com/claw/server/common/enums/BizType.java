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
    OPERATOR_REVENUE,
    /** 项目专属核算（V36 项目管理域：每项目独立双记账）。 */
    PROJECT_LEDGER,
    /** 容量预订预付（V71：用户定购产能单位，预付产能款直付厂家托管）。 */
    CAPACITY_SUBSCRIPTION,
    /** 容量回佣（V71：从厂家 owner_share 计提，按定购单位比例自动分成）。 */
    CAPACITY_REBATE,
    /** 履约异步结算（取货扫码触发：释放冻结 + 服务站提成 + 厂家货款，复用 ledger 双记账）。 */
    FULFILLMENT_SETTLEMENT,
    /** 任务大厅结算（任务报酬 publisher 出账 / provider 入账，复用 ledger 双记账，P0）。 */
    TASK_SETTLEMENT,
    /** 清分分账过账（V128 资金路由：R1 扫码购 / R5 共享池分成通用）。 */
    CLEARING_SETTLE,
    /** 采购段过账（V128 资金路由：应付厂家，购销模式）。 */
    PURCHASE_SETTLE,
    /** 销售段过账（V128 资金路由：应收用户，购销模式）。 */
    SALES_SETTLE,
    /** 货款直结厂家/站（V128 资金路由：代付语义，区别于用户 WITHDRAW）。 */
    MERCHANT_PAYOUT,
    /** 差错挂账调整（V128 资金路由：差异差额入 SUSPENSE 科目）。 */
    SUSPENSE_ADJUST,
    /** 平台自有分润再分配（V128 资金路由：R10，平台花自己的钱分发推广者）。 */
    PLATFORM_SPLIT_RE
}
