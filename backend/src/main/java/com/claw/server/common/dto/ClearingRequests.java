package com.claw.server.common.dto;

import com.claw.server.common.enums.ClearingMode;
import com.claw.server.common.enums.ClearingScene;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 清分域请求（clearing 入参，遵循 {@link LedgerRequests} record 风格）。
 *
 * <p>仅承载原始输入（枚举/标量），<b>不</b>引用任何 domain 实体——保持 common 层对 domain 零依赖。
 */
public final class ClearingRequests {

    private ClearingRequests() {
    }

    /**
     * 统一清分请求（R1/R5 通用入口）。
     *
     * @param scene          清分场景（R1..R12，写入 clearing_instruction.scene）
     * @param bizScene       业务场景（settlement_rule.biz_scene，如 CONSIGNMENT_SCAN）
     * @param basisRef       依据单号（订单号等；账本 bizRef 前缀 + 指令 basis_ref）
     * @param total          待清分总额
     * @param manufacturerId 可选：按厂家细分规则
     * @param currency       币种（空则默认 USD）
     * @param mode           清分时机模式（空则默认 AT_SOURCE）
     * @param channel        通道标识（可空，AT_SOURCE 时由通道 SPI 决定，见 T06）
     */
    public record Settle(
            @NotNull ClearingScene scene,
            @NotNull String bizScene,
            @NotNull String basisRef,
            @NotNull @Positive BigDecimal total,
            Long manufacturerId,
            String currency,
            ClearingMode mode,
            String channel) {
    }
}
