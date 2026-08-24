package com.claw.server.domain.payment;

import com.claw.server.common.enums.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 支付网关工厂（v2.0 — 修改5：多方资金托管）。
 *
 * <p>职责：
 * <ul>
 *   <li>根据请求类型选择合适的支付网关</li>
 *   <li>支持多网关容灾（主网关失败自动切换备用）</li>
 *   <li>统一对外提供网关能力</li>
 * </ul>
 *
 * <p>容灾策略：
 * <ol>
 *   <li>默认使用 BANK 类型（ABA）</li>
 *   <li>BANK 失败 → 切换 CLEARING 类型（Bakong）</li>
 *   <li>所有网关失败 → 返回错误</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentGatewayFactory {

    private final List<PaymentGatewayProvider> providers;

    /**
     * 按类型获取网关。
     */
    public PaymentGatewayProvider getProvider(ProviderType type) {
        return providers.stream()
                .filter(p -> p.getProviderType() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No payment provider for type: " + type));
    }

    /**
     * 获取默认网关（BANK 类型 = ABA）。
     */
    public PaymentGatewayProvider getDefaultProvider() {
        return getProvider(ProviderType.BANK);
    }

    /**
     * 获取所有网关映射（type → provider）。
     */
    public Map<ProviderType, PaymentGatewayProvider> getAllProviders() {
        return providers.stream()
                .collect(Collectors.toMap(PaymentGatewayProvider::getProviderType, Function.identity()));
    }

    /**
     * 容灾收单：尝试主网关，失败切换备用。
     */
    public String createQrCodeWithFallback(String orderNo, BigDecimal amountUsd) {
        // 尝试 BANK (ABA)
        try {
            return getProvider(ProviderType.BANK).createQrCode(orderNo, amountUsd);
        } catch (Exception e) {
            log.warn("ABA 收单失败，切换 Bakong：{}", e.getMessage());
            // 尝试 CLEARING (Bakong)
            try {
                return getProvider(ProviderType.CLEARING).createQrCode(orderNo, amountUsd);
            } catch (Exception e2) {
                log.error("所有网关收单失败 orderNo={}", orderNo);
                throw new IllegalStateException("All payment providers failed for order: " + orderNo);
            }
        }
    }

    /**
     * 容灾出金：尝试主网关，失败切换备用。
     */
    public String payoutWithFallback(String txnNo, BigDecimal amountUsd, String bankAccountJson) {
        try {
            return getProvider(ProviderType.BANK).payout(txnNo, amountUsd, bankAccountJson);
        } catch (Exception e) {
            log.warn("ABA 出金失败，切换 Bakong：{}", e.getMessage());
            try {
                return getProvider(ProviderType.CLEARING).payout(txnNo, amountUsd, bankAccountJson);
            } catch (Exception e2) {
                log.error("所有网关出金失败 txnNo={}", txnNo);
                throw new IllegalStateException("All payment providers failed for txn: " + txnNo);
            }
        }
    }
}
