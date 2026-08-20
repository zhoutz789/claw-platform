package com.claw.server.domain.payment;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ABA 银行网关适配器（技术文档 4.1）。
 *
 * <p>生产实现对接 ABA 开放接口；当前提供 {@link AbaGatewayMock} 模拟实现，
 * 用于本地与测试环境跑通「KHQR 收单 → Bakong 清算 → 出金 → 日流水」全链路。
 */
public interface AbaGateway {

    /**
     * 生成 KHQR 收单码。
     *
     * @param orderNo   平台支付单号
     * @param amountUsd 收单金额（USD）
     * @return KHQR 收单字符串（生产为可扫码的 EMVCo 数据串）
     */
    String createKhqr(String orderNo, BigDecimal amountUsd);

    /**
     * ABA 出金（提现打款）。
     *
     * @param txnNo          平台提现单号
     * @param amountUsd      出金金额（USD）
     * @param bankAccountJson 收款账户信息（JSON）
     * @return ABA 侧流水号
     */
    String payout(String txnNo, BigDecimal amountUsd, String bankAccountJson);

    /**
     * 拉取 ABA 某日资金净额（T+1 对账用）。
     *
     * @param date 对账日
     * @return 当日净额（流入为正、流出为负）
     */
    BigDecimal fetchDailyNetAmount(LocalDate date);
}
