package com.claw.server.domain.payment;

import com.claw.server.common.enums.ProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Bakong 国家清算网关适配器（v2.0 — 修改5 多方合作）。
 *
 * <p>Bakong 是柬埔寨国家清算系统，支持跨银行实时转账。
 * 作为 ABA 之外的备用网关，资金分散到多个通道降低单点风险。
 *
 * <p>生产实现对接 Bakong API；当前为模拟实现。
 */
@Component
@ConditionalOnProperty(name = "claw.payment.bakong.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class BakongGatewayProvider implements PaymentGatewayProvider {

    @Value("${claw.payment.bakong.api-url:https://api.bakong.nbc.gov.kh}")
    private String apiUrl;

    @Override
    public String createQrCode(String orderNo, BigDecimal amountUsd) {
        // 生产：调用 Bakong API 生成 QR
        String qr = "BKNG://claw/" + orderNo + "?amt=" + amountUsd.toPlainString();
        log.info("Bakong 生成收单码：{}", qr);
        return qr;
    }

    @Override
    public String payout(String txnNo, BigDecimal amountUsd, String bankAccountJson) {
        // 生产：调用 Bakong 实时转账 API
        String ref = "BKNG-PAYOUT-" + txnNo;
        log.info("Bakong 出金：{} 金额 ${}", ref, amountUsd);
        return ref;
    }

    @Override
    public BigDecimal fetchDailyNetAmount(LocalDate date) {
        // 生产：从 Bakong 对账 API 拉取
        BigDecimal net = BigDecimal.ZERO;
        log.info("Bakong 日流水净额：{} → {}", date, net);
        return net;
    }

    @Override
    public ProviderType getProviderType() {
        return ProviderType.CLEARING;
    }

    @Override
    public String getDisplayName() {
        return "Bakong NBC";
    }
}
