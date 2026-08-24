package com.claw.server.common.enums;

/**
 * 管理权转移类型（对应 V14 custody_transfers.transfer_type）。
 */
public enum TransferType {
    SWAP_EXCHANGE,
    RENTAL_START,
    RENTAL_END,
    SHARED_POOL_ENTRY,
    SHARED_POOL_EXIT,
    RECOVERY,
    TRADE_IN,
    INITIAL_PURCHASE
}
