package com.claw.server.domain.risk;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.RiskMonitorStatus;
import com.claw.server.common.enums.RiskMetricType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 风控监控服务（V16, Phase 2）。
 *
 * <p>职责：
 * <ul>
 *   <li>风控指标记录与监控</li>
 *   <li>自动熔断触发</li>
 *   <li>保险基金管理</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskMonitorService {

    private final StationRiskMonitorRepository monitorRepository;
    private final InsuranceFundRepository fundRepository;
    private final PaymentDefaultLockTrigger paymentDefaultLockTrigger;

    /**
     * 记录风控指标（DB 触发器自动评分）。
     */
    @Transactional
    public StationRiskMonitor recordMetric(Long stationId, Long operatorId,
                                           RiskMetricType metricType,
                                           BigDecimal metricValue, BigDecimal threshold,
                                           BigDecimal baseline, String reason) {
        StationRiskMonitor monitor = StationRiskMonitor.builder()
                .stationId(stationId)
                .operatorId(operatorId)
                .metricType(metricType)
                .metricValue(metricValue)
                .threshold(threshold)
                .baseline(baseline)
                .triggeredReason(reason)
                .build();

        monitor = monitorRepository.save(monitor);

        if (monitor.getStatus() == RiskMonitorStatus.CIRCUIT_BREAK) {
            log.warn("站点熔断触发 stationId={} metric={} score={}",
                    stationId, metricType, monitor.getRiskScore());
        }

        return monitor;
    }

    /**
     * 解除风控预警。
     */
    @Transactional
    public StationRiskMonitor resolveAlert(Long monitorId, Long resolvedBy, String note) {
        StationRiskMonitor monitor = monitorRepository.findById(monitorId)
                .orElseThrow(() -> new BizException(BizException.NOT_FOUND, "error.monitor.not.found"));

        monitor.setStatus(RiskMonitorStatus.RESOLVED);
        monitor.setResolvedBy(resolvedBy);
        monitor.setResolvedAt(Instant.now());
        monitor.setResolutionNote(note);
        monitor.setUpdatedAt(Instant.now());
        return monitorRepository.save(monitor);
    }

    /**
     * 获取所有活跃风控预警。
     */
    @Transactional(readOnly = true)
    public List<StationRiskMonitor> listActiveAlerts() {
        return monitorRepository.findByStatusInAndDeletedFalse(List.of(
                RiskMonitorStatus.WARNING,
                RiskMonitorStatus.CRITICAL,
                RiskMonitorStatus.CIRCUIT_BREAK));
    }

    /**
     * 获取保险基金状态。
     */
    @Transactional(readOnly = true)
    public InsuranceFund getFundStatus() {
        return fundRepository.findFirstByDeletedFalse()
                .orElseGet(() -> InsuranceFund.builder().build());
    }

    /**
     * 更新保险基金余额（交易计提时调用）。
     */
    @Transactional
    public InsuranceFund updateFundBalance(BigDecimal collection, BigDecimal payout) {
        InsuranceFund fund = fundRepository.findFirstByDeletedFalse()
                .orElseGet(() -> {
                    InsuranceFund f = InsuranceFund.builder().build();
                    return fundRepository.save(f);
                });

        if (collection != null) {
            fund.setTotalBalance(fund.getTotalBalance().add(collection));
            fund.setTotalCollected(fund.getTotalCollected().add(collection));
        }
        if (payout != null) {
            fund.setTotalBalance(fund.getTotalBalance().subtract(payout));
            fund.setTotalClaimed(fund.getTotalClaimed().add(payout));
        }

        fund.setLastUpdatedAt(Instant.now());
        return fundRepository.save(fund);
    }

    /**
     * 检查站点是否已被熔断。
     */
    @Transactional(readOnly = true)
    public boolean isCircuitBroken(Long stationId) {
        List<StationRiskMonitor> monitors = monitorRepository.findByStationIdAndDeletedFalse(stationId);
        return monitors.stream().anyMatch(m -> m.getStatus() == RiskMonitorStatus.CIRCUIT_BREAK);
    }

    /**
     * 车辆欠费断缴 → 触发平台锁车（T3 控车挂钩点）。
     *
     * <p>风控判定欠费后调用，经 {@code PaymentDefaultLockTrigger} 对车辆下发断缴锁车指令。
     * 当前 {@code RiskMonitorService} 聚焦站点风控，本方法作为车辆欠费锁车的统一入口预留，
     * 后续车辆欠费检测器可直接调用。
     */
    @Transactional
    public void triggerVehiclePaymentDefaultLock(Long assetId) {
        paymentDefaultLockTrigger.onPaymentDefault(assetId);
    }
}
