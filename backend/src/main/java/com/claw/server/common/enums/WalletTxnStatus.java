package com.claw.server.common.enums;

/**
 * 钱包交易状态（对应 claw.wallet_txns.status，V7 定义）。
 * 充值 RECHARGE：CREATED → PAID（回调对账后入账）。
 * 提现 WITHDRAW：CREATED → PROCESSING（账本已扣减）→ SUCCESS / FAILED_REFUND（失败退回账本）。
 */
public enum WalletTxnStatus {
    /** 已创建。 */
    CREATED,
    /** 充值已入账。 */
    PAID,
    /** 提现处理中（资金已从账本扣减，等待 ABA 出金回执）。 */
    PROCESSING,
    /** 提现成功（ABA 出金完成）。 */
    SUCCESS,
    /** 提现失败，资金退回账本。 */
    FAILED_REFUND
}
