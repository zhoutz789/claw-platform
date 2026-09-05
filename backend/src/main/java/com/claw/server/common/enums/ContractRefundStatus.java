package com.claw.server.common.enums;

/**
 * 合约保证金退款状态（对应 V73 claw.station_contracts.refund_status）。
 *
 * <ul>
 *   <li>NONE —— 未发生退款（合约生效中/未退出）；</li>
 *   <li>PENDING —— 已申请退出，保证金清算中（应在 refund_due_at 前完成）；</li>
 *   <li>REFUNDED —— 全额退还；</li>
 *   <li>DEDUCTED —— 扣除残值/违约金后部分退还（refund_amount 记录实退额）。</li>
 * </ul>
 */
public enum ContractRefundStatus {
    NONE,
    PENDING,
    REFUNDED,
    DEDUCTED
}
