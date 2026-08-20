package com.claw.server.domain.payment;

import com.claw.server.common.dto.PaymentViews;
import com.claw.server.common.enums.WalletTxnStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 日终对账服务（S4）：技术文档 4.1 每日 T+1 双向对账。
 *
 * <p>平台侧净额 = 当日已入账充值/三专户收单（正）− 提现成功（负）；
 * 银行侧净额 = ABA 日流水净额（{@link AbaGateway#fetchDailyNetAmount}）。
 * 两者一致 → MATCHED；不一致 → MISMATCH 并留痕告警人工处理。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Phnom_Penh");

    private final WalletTxnRepository walletTxnRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final AbaGateway abaGateway;

    @Transactional
    public PaymentViews.ReconciliationView runDaily(LocalDate date) {
        Instant from = date.atStartOfDay(ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<WalletTxn> txns = walletTxnRepository.findByCreatedAtBetween(from, to);
        BigDecimal platformNet = BigDecimal.ZERO;
        int settledCount = 0;
        for (WalletTxn t : txns) {
            boolean in = "RECHARGE".equals(t.getTxnType()) || "ESCROW_COLLECT".equals(t.getTxnType());
            boolean out = "WITHDRAW".equals(t.getTxnType());
            if (in && t.getStatus() == WalletTxnStatus.PAID) {
                platformNet = platformNet.add(t.getAmountUsd());
                settledCount++;
            } else if (out && t.getStatus() == WalletTxnStatus.SUCCESS) {
                platformNet = platformNet.subtract(t.getAmountUsd());
                settledCount++;
            }
            // 提现 FAILED_REFUND 净额为 0（扣减又退回），不参与净额
        }

        BigDecimal bankNet = abaGateway.fetchDailyNetAmount(date);
        boolean matched = platformNet.compareTo(bankNet) == 0;
        String detail = matched ? null
                : "{\"platformTotal\":" + platformNet.toPlainString()
                + ",\"bankTotal\":" + bankNet.toPlainString()
                + ",\"diff\":" + platformNet.subtract(bankNet).toPlainString() + "}";

        ReconciliationRun run = reconciliationRunRepository.findByRunDate(date)
                .orElseGet(() -> ReconciliationRun.builder().runDate(date).build());
        run.setStatus(matched ? "MATCHED" : "MISMATCH");
        run.setPlatformTotal(platformNet);
        run.setBankTotal(bankNet);
        run.setMatchedCount(settledCount);
        run.setMismatchCount(matched ? 0 : 1);
        run.setDetailJson(detail);
        ReconciliationRun saved = reconciliationRunRepository.save(run);

        log.info("日终对账 {}：平台 ${} 银行 ${} → {}", date, platformNet, bankNet, run.getStatus());
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public PaymentViews.ReconciliationView latest() {
        return reconciliationRunRepository.findFirstByOrderByRunDateDesc()
                .map(this::toView)
                .orElse(null);
    }

    private PaymentViews.ReconciliationView toView(ReconciliationRun r) {
        return new PaymentViews.ReconciliationView(r.getRunDate(), r.getStatus(),
                r.getPlatformTotal(), r.getBankTotal(), r.getMatchedCount(),
                r.getMismatchCount(), r.getDetailJson(), r.getCreatedAt());
    }
}
