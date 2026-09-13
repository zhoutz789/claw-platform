package com.claw.server.common.enums;

/**
 * 账户类型（对应 claw.accounts.account_type，V1 定义）。
 * 平台内部户 user_id 为空；用户总账户/资产子账户/押金冻结户关联具体 user/asset。
 */
public enum AccountType {
    /** 总账户：用户/平台主账户。 */
    MASTER,
    /** 资产账户：按车辆/电池等资产开立的子账户。 */
    ASSET,
    /** 功能子账户。 */
    SUB,
    /** 押金冻结户：换电/租用押金 HELD 状态存放处。 */
    DEPOSIT_LOCKED,
    /** 残值准备金专户（D27 escrow：资金所有权归用户/投资者）。 */
    RESIDUAL_RESERVE,
    /** 电池基金专户（换电基金计提归集）。 */
    BATTERY_FUND,
    /** 车辆风险准备金专户（分期违约风险缓冲）。 */
    VEHICLE_RISK,
    /** 项目专属核算账户（V36 项目管理域：每项目一账户，走 ledger 双记账）。 */
    PROJECT,
    /** 应付厂家货款（V128 资金路由：平台挂账，负债侧；放款时借记须先贷记）。 */
    PAYABLE_MFG,
    /** 应付服务站佣金（V128 资金路由：站佣负债侧）。 */
    PAYABLE_STATION,
    /** 应付物流（V128 资金路由：物流负债侧）。 */
    PAYABLE_LOGISTICS,
    /** 备付金在途 / 托管桥接（V128 资金路由；建议以 MASTER 户承载，见设计 §1.3）。 */
    CUSTODY_BRIDGE,
    /** 平台服务费 / 佣金收入（V128 资金路由：唯一公司自有资金科目）。 */
    PLATFORM_REVENUE,
    /** 差错专户（V128 资金路由：长款/短款/未匹配/金额不符/汇兑差异挂账）。 */
    SUSPENSE
}
