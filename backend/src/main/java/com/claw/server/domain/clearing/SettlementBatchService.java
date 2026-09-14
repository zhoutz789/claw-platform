package com.claw.server.domain.clearing;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.BatchStatus;
import com.claw.server.common.enums.ClearingScene;
import com.claw.server.common.enums.ClearingStatus;
import com.claw.server.domain.payment.ClearingChannelGateway;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结算批次服务（L5）：周期批量代付的汇总 → 审核 → 下发 → 回执 → 对账闭环（设计 §6.2）。
 *
 * <p>状态机（与 settlement_batch.status 一致）：
 * <pre>
 * COLLECTING ──approve──> REVIEWING ──approve──> APPROVED ──submit──> SENDING
 *                                                       │                  ├─全成功──> SETTLED（终态）
 *                                                       │                  ├─部分──> PARTIAL ──> SENDING（补发）
 *                                                       │                  └─全失败──> MANUAL（终态）
 *                                                       └──failBatch──> MANUAL（终态）
 * </pre>
 *
 * <p>终态 {@link BatchStatus#SETTLED} / {@link BatchStatus#MANUAL} 不可再转移，违例抛
 * {@code error.clearing.batch.illegal.transition}。
 *
 * <p>幂等：{@code batch_no} UNIQUE；同一 {@code (scene, periodStart, periodEnd)} 重复
 * {@link #collect} 直接返回既有批次，不再重复汇总。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementBatchService {

    /** 货款类结算周期配置键（默认 T+7）。 */
    private static final String CYCLE_GOODS_CONFIG = "SETTLE_CYCLE_GOODS";
    /** 平台收入类结算周期配置键（默认 T+30）。 */
    private static final String CYCLE_REVENUE_CONFIG = "SETTLE_CYCLE_PLATFORM_REVENUE";
    private static final int DEFAULT_GOODS_CYCLE = 7;
    private static final int DEFAULT_REVENUE_CYCLE = 30;

    /** 货款/分账类场景（取 SETTLE_CYCLE_GOODS）；其余场景取 SETTLE_CYCLE_PLATFORM_REVENUE。 */
    private static final Set<ClearingScene> GOODS_SCENES = Set.of(ClearingScene.R1, ClearingScene.R5);

    private static final String CYCLE_PATTERN = "T\\+(\\d+)";

    private final ClearingInstructionRepository clearingInstructionRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final SettlementBatchItemRepository settlementBatchItemRepository;
    private final ClearingChannelGateway clearingChannelGateway;
    private final SystemConfigRepository systemConfigRepository;

    // ===================== 汇总 =====================

    /**
     * 周期汇总：收集该周期内 {@code status=CREATED} 的清分指令，生成结算批次。
     *
     * <p>幂等：同一 {@code (scene, periodStart, periodEnd)} 已汇总则返回既有批次。
     * 批次初始状态 {@link BatchStatus#COLLECTING}；应付日 = {@code periodEnd + N}
     * （N 由 {@code SETTLE_CYCLE_GOODS} 等配置决定，货款类默认 7 天，收入类默认 30 天）。
     *
     * @param scene       清分场景（R1..R12）
     * @param periodStart 周期起（含）
     * @param periodEnd   周期止（不含，作为指令 createdAt 的上界）
     * @return 新建或既有的结算批次
     */
    @Transactional
    public SettlementBatch collect(ClearingScene scene, Instant periodStart, Instant periodEnd) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");

        Optional<SettlementBatch> existing = settlementBatchRepository
                .findByBizSceneAndPeriodStartAndPeriodEndAndDeletedFalse(scene.name(), periodStart, periodEnd);
        if (existing.isPresent()) {
            log.info("[SettlementBatch] 同周期批次已存在，跳过重复汇总 batchNo={}", existing.get().getBatchNo());
            return existing.get();
        }
        return doCollect(scene, periodStart, periodEnd);
    }

    private SettlementBatch doCollect(ClearingScene scene, Instant periodStart, Instant periodEnd) {
        List<ClearingInstruction> instructions = clearingInstructionRepository
                .findBySceneAndStatusOrderByCreatedAtAsc(scene, ClearingStatus.CREATED)
                .stream()
                .filter(i -> i.getCreatedAt() != null
                        && !i.getCreatedAt().isBefore(periodStart)
                        && i.getCreatedAt().isBefore(periodEnd))
                .toList();

        int days = resolveCycleDays(scene);
        LocalDate dueDate = periodEnd.atZone(ZoneId.of("UTC")).toLocalDate().plusDays(days);
        Instant now = Instant.now();
        BigDecimal total = instructions.stream()
                .map(ClearingInstruction::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        SettlementBatch batch = SettlementBatch.builder()
                .batchNo(generateBatchNo(scene))
                .bizScene(scene.name())
                .cycle("T+" + days)
                .currency("USD")
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .dueDate(dueDate)
                .totalAmount(total)
                .itemCount(instructions.size())
                .status(BatchStatus.COLLECTING)
                .tenantId(1L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        SettlementBatch saved = settlementBatchRepository.save(batch);

        List<SettlementBatchItem> items = instructions.stream().map(i -> SettlementBatchItem.builder()
                .batchId(saved.getId())
                .clearingInstructionId(i.getId())
                .payeeVsaId(i.getPayeeVsaId())
                .payeeAccountId(i.getPayeeAccountId())
                .amount(i.getAmount())
                .currency(i.getCurrency())
                .status("PENDING")
                .createdAt(now)
                .updatedAt(now)
                .build()).toList();
        settlementBatchItemRepository.saveAll(items);

        log.info("[SettlementBatch] 汇总批次 {} 场景={} 指令数={} 总额={} 应付日={}",
                saved.getBatchNo(), scene, instructions.size(), total, dueDate);
        return saved;
    }

    // ===================== 状态机 =====================

    /**
     * 审核：COLLECTING → REVIEWING → APPROVED（单次调用推进一态；幂等于 APPROVED）。
     *
     * @param batchId   批次 id
     * @param operatorId 审核人 id
     * @return 更新后的批次
     */
    @Transactional
    public SettlementBatch approve(Long batchId, Long operatorId) {
        SettlementBatch batch = get(batchId);
        BatchStatus current = batch.getStatus();
        if (current == BatchStatus.COLLECTING) {
            batch.setStatus(BatchStatus.REVIEWING);
            batch.setUpdatedAt(Instant.now());
            return settlementBatchRepository.save(batch);
        }
        if (current == BatchStatus.REVIEWING) {
            batch.setStatus(BatchStatus.APPROVED);
            batch.setApprovedBy(operatorId);
            batch.setApprovedAt(Instant.now());
            batch.setUpdatedAt(Instant.now());
            return settlementBatchRepository.save(batch);
        }
        if (current == BatchStatus.APPROVED) {
            return batch; // 幂等
        }
        throw illegalTransition(current, BatchStatus.APPROVED);
    }

    /**
     * 下发：APPROVED / PARTIAL → SENDING，逐条调用通道 SPI {@code payoutToPayee} 代付。
     *
     * @param batchId 批次 id
     * @return 更新后的批次（SENDING）
     */
    @Transactional
    public SettlementBatch submit(Long batchId) {
        SettlementBatch batch = get(batchId);
        requireTransition(batch.getStatus(), BatchStatus.APPROVED, BatchStatus.PARTIAL);

        List<SettlementBatchItem> items = settlementBatchItemRepository.findByBatchId(batchId);
        for (SettlementBatchItem item : items) {
            if ("SETTLED".equals(item.getStatus())) {
                continue; // 已结算项不重复下发（补发场景）
            }
            ClearingInstruction ci = item.getClearingInstructionId() != null
                    ? clearingInstructionRepository.findById(item.getClearingInstructionId()).orElse(null)
                    : null;
            String instructionNo = ci != null ? ci.getInstructionNo()
                    : "BATCH-" + batch.getBatchNo() + "-" + item.getId();
            clearingChannelGateway.payoutToPayee(
                    instructionNo, item.getAmount(), buildPayeeJson(item), item.getCurrency());
            item.setStatus("SENT");
            item.setUpdatedAt(Instant.now());
        }
        settlementBatchItemRepository.saveAll(items);

        batch.setStatus(BatchStatus.SENDING);
        batch.setSubmittedAt(Instant.now());
        batch.setUpdatedAt(Instant.now());
        return settlementBatchRepository.save(batch);
    }

    /**
     * 回执：标记每条明细 SETTLED（命中机构回执）或 FAILED（未命中），并据此推进批次状态。
     *
     * <ul>
     *   <li>全部成功 → {@link BatchStatus#SETTLED}（终态）；</li>
     *   <li>部分成功 → {@link BatchStatus#PARTIAL}（调用方再次 {@link #submit} 补发）；</li>
     *   <li>全部失败 → {@link BatchStatus#MANUAL}（终态）。</li>
     * </ul>
     *
     * @param batchId        批次 id
     * @param institutionRefs 成功的机构回执号列表（按明细顺序）
     * @return 更新后的批次
     */
    @Transactional
    public SettlementBatch onBatchAck(Long batchId, List<String> institutionRefs) {
        SettlementBatch batch = get(batchId);
        requireTransition(batch.getStatus(), BatchStatus.SENDING);

        List<SettlementBatchItem> items = settlementBatchItemRepository.findByBatchId(batchId);
        int refCount = institutionRefs == null ? 0 : institutionRefs.size();
        int settled = 0;
        int failed = 0;
        for (int i = 0; i < items.size(); i++) {
            SettlementBatchItem item = items.get(i);
            if ("SETTLED".equals(item.getStatus())) {
                settled++;
                continue;
            }
            if (i < refCount) {
                item.setStatus("SETTLED");
                settled++;
            } else {
                item.setStatus("FAILED");
                failed++;
            }
            item.setUpdatedAt(Instant.now());
        }
        settlementBatchItemRepository.saveAll(items);

        if (items.isEmpty()) {
            batch.setStatus(BatchStatus.SETTLED);
            batch.setSettledAt(Instant.now());
        } else if (failed == 0) {
            batch.setStatus(BatchStatus.SETTLED);
            batch.setSettledAt(Instant.now());
        } else if (settled == 0) {
            batch.setStatus(BatchStatus.MANUAL);
            batch.setFailReason("批次回执全部失败，转人工");
        } else {
            batch.setStatus(BatchStatus.PARTIAL);
        }
        batch.setUpdatedAt(Instant.now());
        return settlementBatchRepository.save(batch);
    }

    /**
     * 失败转人工：任意非终态 → {@link BatchStatus#MANUAL}（终态）。
     *
     * @param batchId 批次 id
     * @param reason  失败原因
     * @return 更新后的批次
     */
    @Transactional
    public SettlementBatch failBatch(Long batchId, String reason) {
        SettlementBatch batch = get(batchId);
        requireTransition(batch.getStatus(), BatchStatus.COLLECTING, BatchStatus.REVIEWING,
                BatchStatus.APPROVED, BatchStatus.SENDING, BatchStatus.PARTIAL, BatchStatus.FAILED);
        batch.setStatus(BatchStatus.MANUAL);
        batch.setFailReason(reason);
        batch.setUpdatedAt(Instant.now());
        return settlementBatchRepository.save(batch);
    }

    // ===================== 内部 =====================

    private SettlementBatch get(Long batchId) {
        return settlementBatchRepository.findById(batchId)
                .filter(b -> !Boolean.TRUE.equals(b.getDeleted()))
                .orElseThrow(() -> BizException.notFound("error.clearing.batch.not.found", batchId));
    }

    private void requireTransition(BatchStatus current, BatchStatus... allowed) {
        if (current == BatchStatus.SETTLED || current == BatchStatus.MANUAL) {
            throw illegalTransition(current, null);
        }
        for (BatchStatus a : allowed) {
            if (a == current) {
                return;
            }
        }
        throw illegalTransition(current, null);
    }

    private static BizException illegalTransition(BatchStatus from, BatchStatus to) {
        return BizException.of(40960, "error.clearing.batch.illegal.transition", from, to);
    }

    private int resolveCycleDays(ClearingScene scene) {
        boolean goods = GOODS_SCENES.contains(scene);
        String key = goods ? CYCLE_GOODS_CONFIG : CYCLE_REVENUE_CONFIG;
        int fallback = goods ? DEFAULT_GOODS_CYCLE : DEFAULT_REVENUE_CYCLE;
        return parseCycleDays(systemConfigRepository.findByConfigKeyAndDeletedFalse(key)
                .map(SystemConfig::getConfigValue)
                .orElse(null), fallback);
    }

    private static int parseCycleDays(String configValue, int fallback) {
        if (configValue == null || configValue.isBlank()) {
            return fallback;
        }
        Matcher m = Pattern.compile(CYCLE_PATTERN).matcher(configValue.trim().toUpperCase());
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        try {
            return Integer.parseInt(configValue.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String generateBatchNo(ClearingScene scene) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "SB-" + scene.name() + "-" + System.currentTimeMillis() + "-" + suffix;
    }

    private static String buildPayeeJson(SettlementBatchItem item) {
        return "{\"vsaId\":" + item.getPayeeVsaId() + ",\"accountId\":" + item.getPayeeAccountId() + "}";
    }
}
