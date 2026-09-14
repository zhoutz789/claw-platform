package com.claw.server.domain.payment;

import com.claw.server.common.dto.PaymentViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.SuspenseDiffType;
import com.claw.server.common.enums.WalletTxnStatus;
import com.claw.server.domain.clearing.SuspenseEntry;
import com.claw.server.domain.clearing.SuspenseEntryRepository;
import com.claw.server.domain.clearing.SuspenseService;
import com.claw.server.domain.funds.FundsLocation;
import com.claw.server.domain.funds.FundsLocationService;
import com.claw.server.domain.ledger.AccountService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final AccountService accountService;
    private final FundsLocationService fundsLocationService;
    private final SuspenseService suspenseService;
    private final SuspenseEntryRepository suspenseEntryRepository;
    private final CustodyBalanceFeed custodyBalanceFeed;

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

    // ===================== L3 / L4 扩展（不改动既有 runDaily / latest） =====================

    /**
     * L3 托管对账：按 {@code funds_location × netByAccountType} 勾对机构托管余额与账本客户资金科目合计。
     *
     * <p>账本侧由 {@link AccountService#sumBalanceByAccountType} 提供；机构侧由
     * {@link CustodyBalanceFeed#balanceFor} 提供（默认实现恒返回 null，即「无上报数据，跳过」）。
     * 任一对 (location, accountType) 不一致 → 落 {@code suspense_entry}（diff_type=AMOUNT_MISMATCH）。
     *
     * @param runId 关联的对账批次 id（reconciliation_runs.id）
     * @param date  对账日
     * @return 差异项数
     */
    @Transactional
    public int reconcileCustodyLayer(Long runId, LocalDate date) {
        int mismatches = 0;
        List<FundsLocation> locations = fundsLocationService.list().stream()
                .filter(l -> FundsLocationService.STATUS_ACTIVE.equals(l.getStatus()))
                .toList();
        for (FundsLocation loc : locations) {
            for (AccountType type : fundsLocationService.coveredAccountTypes(loc.getId())) {
                BigDecimal custody = custodyBalanceFeed.balanceFor(loc, type, date);
                if (custody == null) {
                    continue; // 无托管余额上报，跳过该对勾对
                }
                BigDecimal ledger = accountService.sumBalanceByAccountType(type);
                if (ledger.compareTo(custody) != 0) {
                    suspenseService.record(SuspenseDiffType.AMOUNT_MISMATCH.name(),
                            "CUSTODY:" + loc.getLocationCode(), "LEDGER:" + type.name(),
                            ledger.subtract(custody), "USD", runId);
                    mismatches++;
                }
            }
        }
        log.info("[Reconciliation] L3 托管对账 runId={} date={} 差异数={}", runId, date, mismatches);
        return mismatches;
    }

    /**
     * L4 全量勾对：汇总 L1–L3 未平项，分类写入 {@code suspense_entry}（recon_run_id 关联
     * reconciliation_runs.id），并把四层差异摘要合并进 {@code reconciliation_runs.detail_json}
     * （新增 L3 / L4 段落，保留既有 L1/L2 内容）。
     *
     * @param runId 对账批次 id
     * @return 差异分类摘要（byDiffType → 计数）
     */
    @Transactional
    public Map<String, Object> reconcileDiscrepancyLayer(Long runId) {
        ReconciliationRun run = reconciliationRunRepository.findById(runId)
                .orElseThrow(() -> com.claw.server.common.api.BizException
                        .notFound("error.recon.run.not.found", runId));

        // L1：状态为 MISMATCH 且尚未落 suspense 的，补录一条差异工单
        if ("MISMATCH".equals(run.getStatus())) {
            boolean exists = !suspenseEntryRepository
                    .findByReconRunIdAndChannelRef(runId, "L1:" + runId).isEmpty();
            if (!exists) {
                BigDecimal diff = run.getPlatformTotal().subtract(run.getBankTotal());
                suspenseService.record(SuspenseDiffType.AMOUNT_MISMATCH.name(),
                        "L1:" + runId, "RUN:" + runId, diff, "USD", runId);
            }
        }

        // L4：汇总本 run 关联的全部未平项，按差异类型分类
        List<SuspenseEntry> open = suspenseEntryRepository.findByReconRunId(runId);
        Map<String, Long> byDiff = open.stream()
                .collect(Collectors.groupingBy(SuspenseEntry::getDiffType, Collectors.counting()));
        long l3Count = open.stream()
                .filter(e -> e.getChannelRef() != null && e.getChannelRef().startsWith("CUSTODY:"))
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", runId);
        summary.put("openTotal", open.size());
        summary.put("l3Mismatch", l3Count);
        summary.put("byDiffType", byDiff);

        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("L3", Map.of("mismatches", l3Count));
        layer.put("L4", summary);
        run.setDetailJson(mergeDetail(run.getDetailJson(), layer));
        reconciliationRunRepository.save(run);
        log.info("[Reconciliation] L4 全量勾对 runId={} 未平项={} 分类={}", runId, open.size(), byDiff);
        return summary;
    }

    /** 将新增层摘要合并进既有 detail_json（保留 L1/L2 既有内容）。 */
    private String mergeDetail(String existing, Map<String, Object> layer) {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> root;
        if (existing != null && !existing.isBlank()) {
            try {
                root = mapper.readValue(existing, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                root = new LinkedHashMap<>();
            }
        } else {
            root = new LinkedHashMap<>();
        }
        root.putAll(layer);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return existing;
        }
    }

    private PaymentViews.ReconciliationView toView(ReconciliationRun r) {
        return new PaymentViews.ReconciliationView(r.getRunDate(), r.getStatus(),
                r.getPlatformTotal(), r.getBankTotal(), r.getMatchedCount(),
                r.getMismatchCount(), r.getDetailJson(), r.getCreatedAt());
    }
}
