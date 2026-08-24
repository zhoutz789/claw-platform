package com.claw.server.domain.payment;

import com.claw.server.common.enums.ProviderType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 多方支付网关统一抽象（v2.0 — 修改5：资金托管多方合作）。
 *
 * <p>取代单一 AbaGateway 依赖，支持 ABA / Bakong / Wing / 多方网关灵活切换。
 * 对应修改5：资金托管不是唯一一家，保证接口资金的稳定落地。
 *
 * <p>每个网关实现以下能力：
 * <ul>
 *   <li>{@link #createQrCode} — 生成收单二维码（KHQR / Bakong QR / 其他）</li>
 *   <li>{@link #payout} — 出金打款（提现）</li>
 *   <li>{@link #fetchDailyNetAmount} — 拉取对账日资金净额</li>
 *   <li>{@link #getProviderType} — 标识网关类型</li>
 * </ul>
 */
public interface PaymentGatewayProvider {

    /**
     * 生成收单二维码。
     *
     * @param orderNo   平台支付单号
     * @param amountUsd 收单金额（USD）
     * @return 可扫码的二维码数据串
     */
    String createQrCode(String orderNo, BigDecimal amountUsd);

    /**
     * 出金打款（提现）。
     *
     * @param txnNo           平台提现单号
     * @param amountUsd       出金金额（USD）
     * @param bankAccountJson 收款账户信息（JSON）
     * @return 网关侧流水号
     */
    String payout(String txnNo, BigDecimal amountUsd, String bankAccountJson);

    /**
     * 拉取某日资金净额（T+1 对账用）。
     *
     * @param date 对账日
     * @return 当日净额（流入为正、流出为负）
     */
    BigDecimal fetchDailyNetAmount(LocalDate date);

    /**
     * 标识网关类型。
     */
    ProviderType getProviderType();

    /**
     * 网关显示名称（用于日志/对账报告）。
     */
    String getDisplayName();
}
