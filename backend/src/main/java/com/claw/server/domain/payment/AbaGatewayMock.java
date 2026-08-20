package com.claw.server.domain.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ABA 网关模拟实现：无真实银行接入，返回可追踪的模拟值。
 *
 * <p>生产替换为真实 ABA 客户端（KHQR 收单 + Bakong 清算 + 出金 + 日对账文件拉取）。
 */
@Component
@Slf4j
public class AbaGatewayMock implements AbaGateway {

    @Override
    public String createKhqr(String orderNo, BigDecimal amountUsd) {
        String qr = "KHQR://claw/" + orderNo + "?amt=" + amountUsd.toPlainString();
        log.info("ABA 生成 KHQR 收单码：{}", qr);
        return qr;
    }

    @Override
    public String payout(String txnNo, BigDecimal amountUsd, String bankAccountJson) {
        String ref = "ABA-PAYOUT-" + txnNo;
        log.info("ABA 出金申请：{} 金额 ${} 收款={}", ref, amountUsd, bankAccountJson);
        return ref;
    }

    @Override
    public BigDecimal fetchDailyNetAmount(LocalDate date) {
        // 模拟：对账日与平台侧一致的净额（真实环境从 ABA 对账文件解析）
        BigDecimal net = new BigDecimal("0.00");
        log.info("ABA 拉取日流水净额：{} → {}", date, net);
        return net;
    }
}
