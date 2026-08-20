package com.claw.server.common.dto;

import com.claw.server.common.enums.BizType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/** 账户域请求（ledger 入参）。 */
public final class LedgerRequests {

    private LedgerRequests() {
    }

    /** 单条分录。 */
    public record Entry(
            @NotNull Long accountId,
            @NotNull Direction direction,
            @Positive BigDecimal amount,
            String memo) {
    }

    public enum Direction {
        /** 借：资金从账户流出。 */
        D,
        /** 贷：资金流入账户。 */
        C
    }

    /** 复式记账：同 bizType + bizRef 只能入账一次（幂等）。 */
    public record PostEntries(
            @NotNull BizType bizType,
            String bizRef,
            @NotEmpty @Valid List<Entry> entries) {
    }
}
