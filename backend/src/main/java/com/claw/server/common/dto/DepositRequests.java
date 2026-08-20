package com.claw.server.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** 押金域请求（deposit 入参）。 */
public final class DepositRequests {

    private DepositRequests() {
    }

    /** 支付押金：用户总账户出账 → 押金冻结户入账（HELD）。 */
    public record Hold(
            @NotNull Long userId,
            Long assetId,
            @Positive BigDecimal amount,
            String payOrderNo) {
    }

    /** 归还押金：冻结户出账 → 用户总账户入账（RETURNED）。 */
    public record Release(
            @NotBlank String depositNo) {
    }

    /** 违约扣收：冻结户出账 → 残值准备金专户入账（FORFEITED）。depositNo 走路径。 */
    public record Forfeit(
            String reason) {
    }
}
