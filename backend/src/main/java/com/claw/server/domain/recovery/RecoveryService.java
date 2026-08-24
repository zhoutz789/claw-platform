package com.claw.server.domain.recovery;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.RecoveryOrderStatus;
import com.claw.server.common.enums.RecoveryType;
import com.claw.server.common.enums.ValuationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 残值回收服务（V13, Phase 2 核心）。
 *
 * <p>职责：
 * <ul>
 *   <li>残值评估（三方估价：系统/站方/第三方）</li>
 *   <li>回收订单（现金回收 / 以旧换新）</li>
 *   <li>信用分管理（Claw Score CRUD + 事件触发）</li>
 *   <li>黑名单管理（拉黑/解除）</li>
 * </ul>
 *
 * <p>对应 PRD 4.18：残值回收 + R7 第三方残值评估。
 * 修改7：资产使用寿命不设限 → 任何时候均可回收。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecoveryService {

    private final ResidualValuationRepository valuationRepository;
    private final RecoveryOrderRepository recoveryOrderRepository;
    private final TradeInOrderRepository tradeInOrderRepository;
    private final ClawScoreRepository clawScoreRepository;
    private final StationBlacklistRepository blacklistRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------
    // 1. 残值评估
    // ------------------------------------------------------------------

    /**
     * 创建残值评估请求。
     */
    @Transactional
    public ResidualValuation createValuation(Long assetId, Long ownerUserId,
                                             BigDecimal soh, BigDecimal usageYears,
                                             String brand, String model, Integer cycleCount) {
        ResidualValuation valuation = ResidualValuation.builder()
                .assetId(assetId)
                .ownerUserId(ownerUserId)
                .soh(soh)
                .usageYears(usageYears)
                .brand(brand)
                .model(model)
                .cycleCount(cycleCount)
                .status(ValuationStatus.PENDING)
                .build();

        valuation = valuationRepository.save(valuation);
        log.info("创建残值评估 assetId={} owner={} valuationId={}", assetId, ownerUserId, valuation.getId());
        return valuation;
    }

    /**
     * 提交系统估价（基于 SOH + 循环次数的算法）。
     */
    @Transactional
    public ResidualValuation submitSystemEstimate(Long valuationId, BigDecimal estimate) {
        ResidualValuation v = valuationRepository.findById(valuationId)
                .orElseThrow(() -> BizException.notFound("error.valuation.not.found"));

        v.setSystemEstimate(estimate);
        if (v.getStatus() == ValuationStatus.PENDING) {
            v.setStatus(ValuationStatus.SYSTEM_DONE);
        }
        v.setUpdatedAt(Instant.now());
        v = valuationRepository.save(v);

        log.info("系统估价完成 valuationId={} estimate={}", valuationId, estimate);
        return v;
    }

    /**
     * 提交站方估价。
     */
    @Transactional
    public ResidualValuation submitStationEstimate(Long valuationId, BigDecimal estimate) {
        ResidualValuation v = valuationRepository.findById(valuationId)
                .orElseThrow(() -> BizException.notFound("error.valuation.not.found"));

        v.setStationEstimate(estimate);
        if (v.getStatus() == ValuationStatus.SYSTEM_DONE) {
            v.setStatus(ValuationStatus.STATION_DONE);
        }
        v.setUpdatedAt(Instant.now());
        v = valuationRepository.save(v);

        log.info("站方估价完成 valuationId={} estimate={}", valuationId, estimate);
        return v;
    }

    /**
     * 提交第三方估价（R7 独立评估机构）。
     */
    @Transactional
    public ResidualValuation submitThirdPartyEstimate(Long valuationId, BigDecimal estimate,
                                                      String thirdPartyName, String reportUrl) {
        ResidualValuation v = valuationRepository.findById(valuationId)
                .orElseThrow(() -> BizException.notFound("error.valuation.not.found"));

        v.setThirdPartyEstimate(estimate);
        v.setThirdPartyName(thirdPartyName);
        v.setThirdPartyReportUrl(reportUrl);
        if (v.getStatus() == ValuationStatus.STATION_DONE) {
            v.setStatus(ValuationStatus.THIRD_PARTY_DONE);
        }
        v.setUpdatedAt(Instant.now());
        v = valuationRepository.save(v);
        // 触发器 trg_valuation_final_price 在三方齐全时自动计算 final_price 并置 FINALIZED。
        // 注意：EntityManager.refresh() 不会先 flush 挂起的改动，必须先显式 flush，
        // 否则 refresh 会用数据库旧值覆盖本应写入的 third_party_estimate/status 等字段。
        entityManager.flush();
        entityManager.refresh(v);

        log.info("第三方估价完成 valuationId={} estimate={} by={}", valuationId, estimate, thirdPartyName);
        return v;
    }

    /**
     * 确认最终回收价（取三方中位数，由 DB 触发器自动计算）。
     */
    @Transactional
    public ResidualValuation finalizeValuation(Long valuationId) {
        ResidualValuation v = valuationRepository.findById(valuationId)
                .orElseThrow(() -> BizException.notFound("error.valuation.not.found"));

        if (v.getSystemEstimate() == null || v.getStationEstimate() == null ||
            v.getThirdPartyEstimate() == null) {
            throw BizException.of(42270, "error.valuation.incomplete");
        }

        // DB 触发器已自动计算 finalPrice，此处刷新
        v.setStatus(ValuationStatus.FINALIZED);
        v.setEvaluatedAt(Instant.now());
        v.setUpdatedAt(Instant.now());
        v = valuationRepository.save(v);

        log.info("残值评估定稿 valuationId={} finalPrice={}", valuationId, v.getFinalPrice());
        return v;
    }

    // ------------------------------------------------------------------
    // 2. 回收订单
    // ------------------------------------------------------------------

    /**
     * 创建回收订单（现金回收）。
     */
    @Transactional
    public RecoveryOrder createCashRecovery(Long assetId, Long ownerUserId,
                                            Long valuationId, Long ownershipId,
                                            BigDecimal recoveryPrice, BigDecimal processingFee) {
        String orderNo = "RCV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        BigDecimal fee = processingFee != null ? processingFee : BigDecimal.ZERO;
        RecoveryOrder order = RecoveryOrder.builder()
                .orderNo(orderNo)
                .assetId(assetId)
                .ownerUserId(ownerUserId)
                .ownershipId(ownershipId)
                .valuationId(valuationId)
                .recoveryType(RecoveryType.CASH_RECOVERY)
                .recoveryPrice(recoveryPrice)
                .processingFee(fee)
                .netAmount(recoveryPrice.subtract(fee))
                .status(RecoveryOrderStatus.CREATED)
                .build();

        order = recoveryOrderRepository.save(order);
        log.info("创建现金回收订单 orderNo={} assetId={} price={}", orderNo, assetId, recoveryPrice);
        return order;
    }

    /**
     * 创建以旧换新订单。
     */
    @Transactional
    public RecoveryOrder createTradeIn(Long assetId, Long ownerUserId, Long valuationId,
                                       Long ownershipId, BigDecimal oldValuation,
                                       Long newAssetId, BigDecimal newAssetPrice) {
        String orderNo = "TRD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        BigDecimal difference = newAssetPrice.subtract(oldValuation);

        RecoveryOrder order = RecoveryOrder.builder()
                .orderNo(orderNo)
                .assetId(assetId)
                .ownerUserId(ownerUserId)
                .ownershipId(ownershipId)
                .valuationId(valuationId)
                .recoveryType(RecoveryType.TRADE_IN)
                .recoveryPrice(oldValuation)
                .netAmount(oldValuation)
                .newAssetId(newAssetId)
                .newAssetPrice(newAssetPrice)
                .priceDifference(difference)
                .status(RecoveryOrderStatus.CREATED)
                .build();

        order = recoveryOrderRepository.save(order);

        // 创建以旧换新明细
        TradeInOrder tradeIn = TradeInOrder.builder()
                .orderNo("TIO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .oldAssetId(assetId)
                .oldValuation(oldValuation)
                .newAssetId(newAssetId)
                .newPrice(newAssetPrice)
                .priceDifference(difference)
                .recoveryOrderId(order.getId())
                .ownerUserId(ownerUserId)
                .status("PENDING")
                .build();
        tradeInOrderRepository.save(tradeIn);

        log.info("创建以旧换新订单 orderNo={} oldAsset={} newAsset={} difference={}",
                orderNo, assetId, newAssetId, difference);
        return order;
    }

    /**
     * 所有人确认回收价格。
     */
    @Transactional
    public RecoveryOrder confirmRecovery(Long orderId) {
        RecoveryOrder order = recoveryOrderRepository.findById(orderId)
                .orElseThrow(() -> BizException.notFound("error.recovery.not.found"));

        order.setStatus(RecoveryOrderStatus.OWNER_CONFIRMED);
        order.setConfirmedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        order = recoveryOrderRepository.save(order);

        log.info("回收确认 orderId={} orderNo={}", orderId, order.getOrderNo());
        return order;
    }

    /**
     * 完成回收（资金入账 + 产权状态更新）。
     */
    @Transactional
    public RecoveryOrder completeRecovery(Long orderId, String ledgerTxnId) {
        RecoveryOrder order = recoveryOrderRepository.findById(orderId)
                .orElseThrow(() -> BizException.notFound("error.recovery.not.found"));

        if (order.getStatus() != RecoveryOrderStatus.OWNER_CONFIRMED) {
            throw BizException.of(40972, "error.recovery.not.confirmed");
        }

        order.setLedgerTxnId(ledgerTxnId);
        order.setStatus(RecoveryOrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        order = recoveryOrderRepository.save(order);

        log.info("回收完成 orderId={} orderNo={} netAmount={}", orderId, order.getOrderNo(), order.getNetAmount());
        return order;
    }

    // ------------------------------------------------------------------
    // 3. 信用分管理
    // ------------------------------------------------------------------

    /**
     * 获取用户信用分（不存在则创建默认 650 分）。
     */
    @Transactional
    public ClawScore getOrCreateScore(Long userId) {
        return clawScoreRepository.findByUserIdAndDeletedFalse(userId)
                .orElseGet(() -> {
                    ClawScore score = ClawScore.builder()
                            .userId(userId)
                            .score(650)
                            .scoreLevel("C")
                            .build();
                    return clawScoreRepository.save(score);
                });
    }

    /**
     * 更新信用分（加减分）。
     */
    @Transactional
    public ClawScore updateScore(Long userId, int delta, String eventType, String detail) {
        ClawScore score = getOrCreateScore(userId);
        int newScore = Math.max(0, Math.min(1000, score.getScore() + delta));
        score.setScore(newScore);
        score.setLastEventType(eventType);
        score.setLastEventDetail(detail);
        score.setUpdatedAt(Instant.now());
        score = clawScoreRepository.save(score);

        log.info("信用分更新 userId={} delta={} newScore={}", userId, delta, newScore);
        return score;
    }

    /**
     * 检查用户是否满足个人站准入门槛（score >= 650）。
     */
    @Transactional(readOnly = true)
    public boolean canOpenStation(Long userId) {
        return clawScoreRepository.findByUserIdAndDeletedFalse(userId)
                .map(score -> score.getScore() >= 650)
                .orElse(false);
    }

    // ------------------------------------------------------------------
    // 4. 黑名单管理
    // ------------------------------------------------------------------

    /**
     * 拉黑用户/站点。
     */
    @Transactional
    public StationBlacklist blacklist(com.claw.server.common.enums.BlacklistType type,
                                      Long targetId, String reason, String description,
                                      Long blacklistedBy, Instant expiresAt) {
        StationBlacklist entry = StationBlacklist.builder()
                .userId(type == com.claw.server.common.enums.BlacklistType.USER_BANNED ? targetId : null)
                .stationId(type == com.claw.server.common.enums.BlacklistType.STATION_BANNED ? targetId : null)
                .blacklistType(type)
                .reason(reason)
                .description(description)
                .blacklistedBy(blacklistedBy)
                .expiresAt(expiresAt)
                .build();

        entry = blacklistRepository.save(entry);
        log.info("拉黑 type={} targetId={} reason={}", type, targetId, reason);
        return entry;
    }

    /**
     * 检查用户是否被拉黑。
     */
    @Transactional(readOnly = true)
    public boolean isUserBanned(Long userId) {
        return !blacklistRepository.findByUserIdAndResolvedFalseAndDeletedFalse(userId).isEmpty();
    }

    /**
     * 解除黑名单。
     */
    @Transactional
    public StationBlacklist resolveBlacklist(Long blacklistId, Long resolvedBy, String note) {
        StationBlacklist entry = blacklistRepository.findById(blacklistId)
                .orElseThrow(() -> BizException.notFound("error.blacklist.not.found"));

        entry.setResolved(true);
        entry.setResolvedBy(resolvedBy);
        entry.setResolvedAt(Instant.now());
        entry.setResolutionNote(note);
        entry.setUpdatedAt(Instant.now());
        entry = blacklistRepository.save(entry);

        log.info("解除黑名单 blacklistId={}", blacklistId);
        return entry;
    }
}
