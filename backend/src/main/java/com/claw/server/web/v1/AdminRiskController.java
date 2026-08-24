package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.AdminDtos.*;
import com.claw.server.common.enums.RiskMetricType;
import com.claw.server.common.enums.RiskMonitorStatus;
import com.claw.server.domain.operator.OperatorRiskEvent;
import com.claw.server.domain.operator.OperatorRiskEventRepository;
import com.claw.server.domain.risk.InsuranceFund;
import com.claw.server.domain.risk.InsuranceFundRepository;
import com.claw.server.domain.risk.RiskMonitorService;
import com.claw.server.domain.risk.StationRiskMonitor;
import com.claw.server.domain.risk.StationRiskMonitorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 后台风控模块（S5 补齐）：风控监控 + 异常告警 + 保险基金。
 *
 * <p>GET  /risk/monitors                站点风控指标监控列表（可维护）
 * POST /risk/monitors                新增监控项
 * PUT  /risk/monitors/{id}           修改监控项
 * DELETE /risk/monitors/{id}         软删除监控项
 * GET  /risk/events?resolved=false   风控事件（异常告警）列表
 * POST /risk/events/{id}/resolve     处置/解除风控事件
 * GET  /risk/insurance-fund          保险基金池
 * PUT  /risk/insurance-fund/{id}     调整保险基金
 */
@RestController
@RequestMapping("/api/v1/admin/risk")
@RequiredArgsConstructor
public class AdminRiskController {

    private final StationRiskMonitorRepository monitorRepository;
    private final OperatorRiskEventRepository eventRepository;
    private final InsuranceFundRepository fundRepository;
    private final RiskMonitorService riskMonitorService;

    @GetMapping("/monitors")
    public ApiResult<List<StationRiskMonitorView>> listMonitors() {
        return ApiResult.ok(monitorRepository.findAll().stream()
                .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
                .map(this::toMonitorView).toList());
    }

    @PostMapping("/monitors")
    public ApiResult<StationRiskMonitorView> createMonitor(@RequestBody StationRiskMonitorReq req) {
        // 走 RiskMonitorService，触发 V16 触发器真实评分/熔断（此前直接用 repository 绕过业务逻辑）
        StationRiskMonitor m = riskMonitorService.recordMetric(req.stationId(), req.operatorId(),
                req.metricType() == null ? RiskMetricType.BOND_SHORTFALL
                        : RiskMetricType.valueOf(req.metricType()),
                req.metricValue(), req.threshold(), req.baseline(), req.triggeredReason());
        return ApiResult.ok(toMonitorView(m));
    }

    @PutMapping("/monitors/{id}")
    public ApiResult<StationRiskMonitorView> updateMonitor(@PathVariable Long id,
                                                           @RequestBody StationRiskMonitorReq req) {
        StationRiskMonitor m = monitorRepository.findById(id)
                .orElseThrow(() -> new com.claw.server.common.api.BizException(40401, "risk.monitor.not.found"));
        if (req.stationId() != null) m.setStationId(req.stationId());
        if (req.operatorId() != null) m.setOperatorId(req.operatorId());
        if (req.metricType() != null) m.setMetricType(
                com.claw.server.common.enums.RiskMetricType.valueOf(req.metricType()));
        if (req.metricValue() != null) m.setMetricValue(req.metricValue());
        if (req.threshold() != null) m.setThreshold(req.threshold());
        if (req.baseline() != null) m.setBaseline(req.baseline());
        if (req.riskScore() != null) m.setRiskScore(req.riskScore());
        if (req.status() != null) m.setStatus(RiskMonitorStatus.valueOf(req.status()));
        if (req.triggeredReason() != null) m.setTriggeredReason(req.triggeredReason());
        if (req.resolutionNote() != null) m.setResolutionNote(req.resolutionNote());
        m.setUpdatedAt(Instant.now());
        return ApiResult.ok(toMonitorView(monitorRepository.save(m)));
    }

    @DeleteMapping("/monitors/{id}")
    public ApiResult<Void> deleteMonitor(@PathVariable Long id) {
        StationRiskMonitor m = monitorRepository.findById(id)
                .orElseThrow(() -> new com.claw.server.common.api.BizException(40401, "risk.monitor.not.found"));
        m.setDeleted(true);
        m.setUpdatedAt(Instant.now());
        monitorRepository.save(m);
        return ApiResult.ok();
    }

    @GetMapping("/events")
    public ApiResult<List<OperatorRiskEventView>> listEvents(
            @RequestParam(required = false) Boolean resolved) {
        List<OperatorRiskEvent> list = eventRepository.findAll().stream()
                .filter(e -> !Boolean.TRUE.equals(e.getDeleted()))
                .filter(e -> resolved == null || resolved.equals(e.getResolved()))
                .toList();
        return ApiResult.ok(list.stream().map(this::toEventView).toList());
    }

    @PostMapping("/events/{id}/resolve")
    public ApiResult<OperatorRiskEventView> resolveEvent(@PathVariable Long id,
                                                         @RequestBody OperatorRiskEventResolveReq req) {
        OperatorRiskEvent e = eventRepository.findById(id)
                .orElseThrow(() -> new com.claw.server.common.api.BizException(40401, "risk.event.not.found"));
        e.setResolved(true);
        e.setResolvedBy(req.resolvedBy());
        e.setResolutionNote(req.resolutionNote());
        e.setResolvedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return ApiResult.ok(toEventView(eventRepository.save(e)));
    }

    @GetMapping("/insurance-fund")
    public ApiResult<List<InsuranceFundView>> listFunds() {
        return ApiResult.ok(fundRepository.findAll().stream()
                .filter(f -> !Boolean.TRUE.equals(f.getDeleted()))
                .map(this::toFundView).toList());
    }

    @PutMapping("/insurance-fund/{id}")
    public ApiResult<InsuranceFundView> updateFund(@PathVariable Long id,
                                                   @RequestBody InsuranceFundUpdateReq req) {
        InsuranceFund f = fundRepository.findById(id)
                .orElseThrow(() -> new com.claw.server.common.api.BizException(40401, "insurance.fund.not.found"));
        if (req.totalBalance() != null) f.setTotalBalance(req.totalBalance());
        if (req.coverageRatio() != null) f.setCoverageRatio(req.coverageRatio());
        if (req.status() != null) f.setStatus(com.claw.server.common.enums.FundStatus.valueOf(req.status()));
        if (req.lastUpdatedBy() != null) f.setLastUpdatedBy(req.lastUpdatedBy());
        if (req.auditNotes() != null) f.setAuditNotes(req.auditNotes());
        f.setLastUpdatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return ApiResult.ok(toFundView(fundRepository.save(f)));
    }

    private StationRiskMonitorView toMonitorView(StationRiskMonitor m) {
        return new StationRiskMonitorView(m.getId(), m.getStationId(), m.getOperatorId(),
                m.getMetricType() == null ? null : m.getMetricType().name(), m.getMetricValue(),
                m.getThreshold(), m.getBaseline(), m.getRiskScore(),
                m.getStatus() == null ? null : m.getStatus().name(), m.getTriggeredAt(),
                m.getTriggeredReason(), m.getResolvedAt(), m.getResolvedBy(), m.getResolutionNote(),
                m.getRiskEventId());
    }

    private OperatorRiskEventView toEventView(OperatorRiskEvent e) {
        return new OperatorRiskEventView(e.getId(), e.getOperatorId(), e.getStationId(),
                e.getEventType() == null ? null : e.getEventType().name(),
                e.getSeverity() == null ? null : e.getSeverity().name(), e.getDescription(),
                e.getDetectedValue(), e.getExpectedValue(),
                e.getAutoAction() == null ? null : e.getAutoAction().name(), e.getActionTaken(),
                e.getResolved(), e.getResolvedBy(), e.getResolutionNote(), e.getCreatedAt());
    }

    private InsuranceFundView toFundView(InsuranceFund f) {
        return new InsuranceFundView(f.getId(), f.getTotalBalance(), f.getTotalCollected(),
                f.getTotalClaimed(), f.getTotalRecovered(), f.getCoverageRatio(), f.getTotalAssetValue(),
                f.getCoverageActual(), f.getStatus() == null ? null : f.getStatus().name(),
                f.getLastUpdatedAt(), f.getLastUpdatedBy(), f.getAuditNotes());
    }
}
