package com.claw.server.domain.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ABA 银行网关真实实现（B3）：对接 ABA 开放接口（KHQR 收单 + Bakong 清算 + 出金 + T+1 对账）。
 *
 * <p>仅当 {@code claw.payment.aba.mode=real} 时激活，密钥与端点全部由环境变量注入：
 * <ul>
 *   <li>{@code CLAW_PAYMENT_ABA_API_BASE_URL} — ABA 开放接口基址</li>
 *   <li>{@code CLAW_PAYMENT_ABA_MERCHANT_ID} — 商户号</li>
 *   <li>{@code CLAW_PAYMENT_ABA_API_KEY} — 访问密钥</li>
 * </ul>
 *
 * <p>当前为骨架：构造请求并打印关键日志，真实 HTTP 调用处已用 {@code // REAL CALL} 标注 TODO，
 * 接入 ABA 具体合同时替换即可，调用方（AbaGatewayProvider / PaymentGatewayFactory）无需改动。
 */
@Component
@ConditionalOnProperty(name = "claw.payment.aba.mode", havingValue = "real")
@Slf4j
public class AbaGatewayReal implements AbaGateway {

    @Value("${claw.payment.aba.api-base-url:}")
    private String apiBaseUrl;

    @Value("${claw.payment.aba.merchant-id:}")
    private String merchantId;

    @Value("${claw.payment.aba.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String createKhqr(String orderNo, BigDecimal amountUsd) {
        requireConfigured();
        // TODO 接入 ABA KHQR 收单 API（EMVCo）：POST {apiBaseUrl}/khqr/generate
        // HttpHeaders h = authHeaders(); Map<String,Object> b = Map.of("orderId", orderNo, "amount", amountUsd, "merchantId", merchantId);
        // return restTemplate.postForObject(apiBaseUrl + "/khqr/generate", new HttpEntity<>(b, h), String.class);
        log.info("[REAL ABA] createKhqr order={} amount={} merchant={}（apiKey=****）", orderNo, amountUsd, merchantId);
        return "KHQR-REAL-" + orderNo;
    }

    @Override
    public String payout(String txnNo, BigDecimal amountUsd, String bankAccountJson) {
        requireConfigured();
        // TODO 接入 ABA 出金 API：POST {apiBaseUrl}/payout
        log.info("[REAL ABA] payout txn={} amount={} account={}", txnNo, amountUsd, bankAccountJson);
        return "ABA-PAYOUT-REAL-" + txnNo;
    }

    @Override
    public BigDecimal fetchDailyNetAmount(LocalDate date) {
        requireConfigured();
        // TODO 接入 ABA 对账 API：GET {apiBaseUrl}/reconcile?date=yyyy-MM-dd，解析净额
        log.info("[REAL ABA] fetchDailyNetAmount date={}", date);
        return BigDecimal.ZERO;
    }

    private void requireConfigured() {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalStateException("claw.payment.aba.api-base-url 未配置，无法调用真实 ABA（env: CLAW_PAYMENT_ABA_API_BASE_URL）");
        }
    }
}
