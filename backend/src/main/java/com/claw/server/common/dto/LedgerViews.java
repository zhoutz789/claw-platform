package com.claw.server.common.dto;

import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 账户域视图（ledger 出参，仅依赖 common 层）。 */
public final class LedgerViews {

    private LedgerViews() {
    }

    public static record AccountView(
            Long id, Long userId, AccountType accountType,
            String currency, BigDecimal balance, BigDecimal frozen) {
    }

    public static record EntryView(
            Long id, UUID txnId, Long accountId, String direction,
            BigDecimal amount, String bizType, String bizRef, String memo, Instant createdAt) {
    }

    public static record TxnResult(
            UUID txnId, BizType bizType, String bizRef, int entryCount,
            BigDecimal totalAmount, List<AccountView> touchedAccounts) {
    }
}
