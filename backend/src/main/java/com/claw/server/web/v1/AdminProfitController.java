package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.AdminDtos.*;
import com.claw.server.common.enums.SettlementStatus;
import com.claw.server.domain.sharedpool.RevenueSettlement;
import com.claw.server.domain.sharedpool.RevenueSettlementRepository;
import com.claw.server.domain.sharedpool.RevenueSplitRule;
import com.claw.server.domain.sharedpool.RevenueSplitRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 后台分账模块（S5 补齐）：分账结算报告 + 分成规则维护。
 *
 * <p>GET    /profit/settlements             分账结算记录列表
 * GET    /profit/settlements/{id}          分账结算详情
 * POST   /profit/settlements/{id}/settle   标记结算完成
 * GET    /profit/split-rules               分成规则列表
 * POST   /profit/split-rules               新增分成规则
 * PUT    /profit/split-rules/{id}          修改分成规则
 * DELETE /profit/split-rules/{id}          软删除分成规则
 */
@RestController
@RequestMapping("/api/v1/admin/profit")
@RequiredArgsConstructor
public class AdminProfitController {

    private final RevenueSettlementRepository settlementRepository;
    private final RevenueSplitRuleRepository splitRuleRepository;

    @GetMapping("/settlements")
    public ApiResult<List<RevenueSettlementView>> listSettlements() {
        return ApiResult.ok(settlementRepository.findAll().stream()
                .filter(s -> !Boolean.TRUE.equals(s.getDeleted()))
                .map(this::toSettlementView).toList());
    }

    @GetMapping("/settlements/{id}")
    public ApiResult<RevenueSettlementView> settlementDetail(@PathVariable Long id) {
        RevenueSettlement s = settlementRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "profit.settlement.not.found"));
        return ApiResult.ok(toSettlementView(s));
    }

    @PostMapping("/settlements/{id}/settle")
    public ApiResult<RevenueSettlementView> settle(@PathVariable Long id) {
        RevenueSettlement s = settlementRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "profit.settlement.not.found"));
        s.setStatus(SettlementStatus.SETTLED);
        s.setSettledAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return ApiResult.ok(toSettlementView(settlementRepository.save(s)));
    }

    @GetMapping("/split-rules")
    public ApiResult<List<RevenueSplitRuleView>> listSplitRules() {
        return ApiResult.ok(splitRuleRepository.findAll().stream()
                .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                .map(this::toSplitRuleView).toList());
    }

    @PostMapping("/split-rules")
    public ApiResult<RevenueSplitRuleView> createSplitRule(@RequestBody RevenueSplitRuleReq req) {
        RevenueSplitRule r = RevenueSplitRule.builder()
                .assetId(req.assetId())
                .poolEntryId(req.poolEntryId())
                .ownerRate(req.ownerRate() == null ? BigDecimal.valueOf(0.50) : req.ownerRate())
                .stationRate(req.stationRate() == null ? BigDecimal.valueOf(0.15) : req.stationRate())
                .platformRate(req.platformRate() == null ? BigDecimal.valueOf(0.10) : req.platformRate())
                .insuranceRate(req.insuranceRate() == null ? BigDecimal.valueOf(0.05) : req.insuranceRate())
                .shareBasis(req.shareBasis() == null ? com.claw.server.common.enums.RevenueShareBasis.PER_SWAP
                        : com.claw.server.common.enums.RevenueShareBasis.valueOf(req.shareBasis()))
                .effectiveFrom(req.effectiveFrom())
                .effectiveTo(req.effectiveTo())
                .status(req.status() == null ? "ACTIVE" : req.status())
                .tenantId(1L)
                .deleted(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return ApiResult.ok(toSplitRuleView(splitRuleRepository.save(r)));
    }

    @PutMapping("/split-rules/{id}")
    public ApiResult<RevenueSplitRuleView> updateSplitRule(@PathVariable Long id,
                                                           @RequestBody RevenueSplitRuleReq req) {
        RevenueSplitRule r = splitRuleRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "profit.split.rule.not.found"));
        if (req.assetId() != null) r.setAssetId(req.assetId());
        if (req.poolEntryId() != null) r.setPoolEntryId(req.poolEntryId());
        if (req.ownerRate() != null) r.setOwnerRate(req.ownerRate());
        if (req.stationRate() != null) r.setStationRate(req.stationRate());
        if (req.platformRate() != null) r.setPlatformRate(req.platformRate());
        if (req.insuranceRate() != null) r.setInsuranceRate(req.insuranceRate());
        if (req.shareBasis() != null) r.setShareBasis(
                com.claw.server.common.enums.RevenueShareBasis.valueOf(req.shareBasis()));
        if (req.effectiveFrom() != null) r.setEffectiveFrom(req.effectiveFrom());
        if (req.effectiveTo() != null) r.setEffectiveTo(req.effectiveTo());
        if (req.status() != null) r.setStatus(req.status());
        r.setUpdatedAt(Instant.now());
        return ApiResult.ok(toSplitRuleView(splitRuleRepository.save(r)));
    }

    @DeleteMapping("/split-rules/{id}")
    public ApiResult<Void> deleteSplitRule(@PathVariable Long id) {
        RevenueSplitRule r = splitRuleRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "profit.split.rule.not.found"));
        r.setDeleted(true);
        r.setUpdatedAt(Instant.now());
        splitRuleRepository.save(r);
        return ApiResult.ok();
    }

    private RevenueSettlementView toSettlementView(RevenueSettlement s) {
        return new RevenueSettlementView(s.getId(), s.getSettlementNo(), s.getSettlementDate(),
                s.getStationId(), s.getPoolEntryId(), s.getTotalRevenue(), s.getOwnerShare(),
                s.getStationShare(), s.getPlatformShare(), s.getInsuranceShare(), s.getLedgerTxnId(),
                s.getStatus() == null ? null : s.getStatus().name(), s.getPeriodStart(), s.getPeriodEnd(),
                s.getSettledAt());
    }

    private RevenueSplitRuleView toSplitRuleView(RevenueSplitRule r) {
        return new RevenueSplitRuleView(r.getId(), r.getAssetId(), r.getPoolEntryId(), r.getOwnerRate(),
                r.getStationRate(), r.getPlatformRate(), r.getInsuranceRate(),
                r.getShareBasis() == null ? null : r.getShareBasis().name(), r.getEffectiveFrom(),
                r.getEffectiveTo(), r.getStatus());
    }
}
