package com.claw.server.domain.pv;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 光伏日对账定时任务（VPP 切片最后一块后端）。
 *
 * <p>每天 01:30 跑「昨天」的全站对账；对偏差超阈值的电站打 {@code WARN} 日志。
 *
 * <p><b>渠道说明（本切片范围）：</b>只打日志 + 落库，<b>不接消息/短信等任何外部渠道</b>，
 * 避免引入外部依赖（告警推送在后续切片接入）。落库由 {@link PvReconciliationService#reconcileDay}
 * 完成，本 Job 只负责调度与日志。
 *
 * <p><b>环境保护：</b>受 {@code claw.reconcile.enabled} 开关保护，仅当显式置 {@code true} 才创建
 * Bean（默认 false），避免测试/非生产环境误跑。开关缺失或被置非 true 均不启用。
 */
@Component
@ConditionalOnProperty(prefix = "claw", name = "reconcile.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class PvReconciliationJob {

    private final PvReconciliationService reconciliationService;

    /**
     * 每日对账入口。cron = "0 30 1 * * ?" → 每天 01:30:00 执行，对账日期取昨天。
     */
    @Scheduled(cron = "0 30 1 * * ?")
    public void runDailyReconciliation() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("光伏日对账 Job 启动：对账日期 {}", yesterday);
        List<PvReconciliationService.ReconcileResult> warns =
                reconciliationService.reconcileAll(yesterday);
        for (PvReconciliationService.ReconcileResult warn : warns) {
            log.warn("光伏日对账偏差超阈值 stationAssetId={} day={} deviationWh={} deviationRate={} note={}",
                    warn.stationAssetId(), warn.day(), warn.deviationWh(), warn.deviationRate(), warn.note());
        }
        log.info("光伏日对账 Job 完成：日期 {} 超阈值电站 {} 个", yesterday, warns.size());
    }
}
