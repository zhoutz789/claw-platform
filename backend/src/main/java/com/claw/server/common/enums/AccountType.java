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
    VEHICLE_RISK
}
