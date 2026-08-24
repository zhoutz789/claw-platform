package com.claw.server.domain.payment;

import com.claw.server.common.enums.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ABA 银行网关适配器（v2.0 — 实现 PaymentGatewayProvider）。
 *
 * <p>包装现有 {@link AbaGateway} 接口，适配到统一 {@link PaymentGatewayProvider}。
 * 生产实现对接 ABA 开放接口；当前使用 {@link AbaGatewayMock} 模拟。
 *
 * <p>修改5：资金托管多方合作 → ABA 作为主要网关之一。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AbaGatewayProvider implements PaymentGatewayProvider {

    private final AbaGateway abaGateway;

    @Override
    public String createQrCode(String orderNo, BigDecimal amountUsd) {
        return abaGateway.createKhqr(orderNo, amountUsd);
    }

    @Override
    public String payout(String txnNo, BigDecimal amountUsd, String bankAccountJson) {
        return abaGateway.payout(txnNo, amountUsd, bankAccountJson);
    }

    @Override
    public BigDecimal fetchDailyNetAmount(LocalDate date) {
        return abaGateway.fetchDailyNetAmount(date);
    }

    @Override
    public ProviderType getProviderType() {
        return ProviderType.BANK;
    }

    @Override
    public String getDisplayName() {
        return "ABA Bank";
    }
}
