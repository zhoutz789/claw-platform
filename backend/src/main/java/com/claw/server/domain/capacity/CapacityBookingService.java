package com.claw.server.domain.capacity;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.CapacityPlanStatus;
import com.claw.server.common.enums.CapacitySubscriptionStatus;
import com.claw.server.common.enums.CapacityType;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.claw.server.domain.sharedpool.RentalOrder;
import com.claw.server.domain.sharedpool.RentalOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 共享池容量预订服务（V71, 周老板 2026-09-06 拍板）。
 *
 * <p>职责：
 * <ul>
 *   <li>厂家发布容量预订计划（拆 N 个容量单位）；</li>
 *   <li>用户定购单位、预付产能款（直付厂家托管，平台不经手资金池）；</li>
 *   <li>租赁完成时，从厂家 owner_share 计提回佣，按 unit_count/total_units 二次拆分，自动到各定购单位账户。</li>
 * </ul>
 *
 * <p>合规边界：资产产权始终为厂家单一主体（CONSIGNED），本服务只处理"使用产能/回佣权"，
 * 不持有资产份额；回佣来自厂家自有运营所得、与真实绩效挂钩、不保底不保息。
 *
 * <p>资金域隔离（ArchUnit 铁律）：本服务不直接持有 ledger 仓储，所有余额/流水写入一律经
 * {@link AccountService} / {@link LedgerService}。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CapacityBookingService {

    private final CapacityPlanRepository planRepository;
    private final CapacitySubscriptionRepository subscriptionRepository;
    private final CapacityRebateRuleRepository rebateRuleRepository;
    private final CapacityRebateSettlementRepository rebateSettlementRepository;
    private final RentalOrderRepository rentalOrderRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final SystemConfigRepository systemConfigRepository;

    private static final BigDecimal SCALE4 = BigDecimal.valueOf(4);

    /**
     * 发布容量预订计划。
     */
    @Transactional
    public CapacityPlan createPlan(Long assetId, Long poolEntryId, Long ownerUserId,
                                   Integer totalUnits, BigDecimal unitPrice,
                                   CapacityType capacityType, BigDecimal rebateRate,
                                   Instant windowStart, Instant windowEnd) {
        if (totalUnits == null || totalUnits <= 0) {
            throw BizException.invalidParam("error.capacity.units.invalid");
        }

        // ⑤ 回佣率：缺省取配置默认（CAPACITY_REBATE_RATE_DEFAULT，兜底 0.10）；
        // 显式传入但超上限（CAPACITY_REBATE_RATE_MAX，兜底 0.30）则拒绝建计划。
        BigDecimal effectiveRebateRate;
        if (rebateRate == null) {
            effectiveRebateRate = defaultRebateRate();
        } else if (rebateRate.compareTo(maxRebateRate()) > 0) {
            throw BizException.of(40973, "error.capacity.rebate.rate.exceed");
        } else {
            effectiveRebateRate = rebateRate;
        }

        CapacityPlan plan = CapacityPlan.builder()
                .assetId(assetId)
                .poolEntryId(poolEntryId)
                .ownerUserId(ownerUserId)
                .totalUnits(totalUnits)
                .subscribedUnits(0)
                .unitPrice(unitPrice)
                .capacityType(capacityType != null ? capacityType : CapacityType.SERIAL)
                .rebateRate(effectiveRebateRate)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .status(CapacityPlanStatus.OPEN)
                .build();
        plan = planRepository.save(plan);

        // 同步默认回佣规则
        rebateRuleRepository.save(CapacityRebateRule.builder()
                .planId(plan.getId())
                .rebateRate(plan.getRebateRate())
                .minPayout(BigDecimal.valueOf(0.01))
                .status("ACTIVE")
                .build());

        log.info("发布容量预订计划 planId={} assetId={} totalUnits={} owner={}",
                plan.getId(), assetId, totalUnits, ownerUserId);
        return plan;
    }

    /**
     * 用户定购容量单位：预付产能款直付厂家托管（平台不经手资金池）。
     *
     * @return 定购记录（已落 ledger 预付单据）
     */
    @Transactional
    public CapacitySubscription subscribe(Long planId, Long subscriberUserId, Integer unitCount) {
        CapacityPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> BizException.notFound("error.capacity.plan.not.found"));
        if (plan.getStatus() != CapacityPlanStatus.OPEN) {
            throw BizException.of(40970, "error.capacity.plan.not.open");
        }
        if (unitCount == null || unitCount <= 0) {
            throw BizException.invalidParam("error.capacity.unit.invalid");
        }
        // ④ CapacityType 语义分支：
        // SERIAL（串行独占）：至多一个定购行，重复定购直接拒绝（40971）；
        // PARALLEL（并行共享）：允许多行 top-up，但单订户累计份数不得超总容量（40972）。
        if (plan.getCapacityType() == CapacityType.SERIAL) {
            if (subscriptionRepository.existsByPlanIdAndSubscriberUserIdAndDeletedFalse(planId, subscriberUserId)) {
                throw BizException.of(40971, "error.capacity.already.subscribed");
            }
            if (plan.getSubscribedUnits() + unitCount > plan.getTotalUnits()) {
                throw BizException.of(40972, "error.capacity.units.exceed");
            }
        } else {
            Long ownedUnits = subscriptionRepository.sumUnitCountByPlanIdAndSubscriberUserId(planId, subscriberUserId);
            long already = ownedUnits == null ? 0L : ownedUnits;
            if (already + unitCount > plan.getTotalUnits()) {
                throw BizException.of(40972, "error.capacity.units.exceed");
            }
        }

        BigDecimal prepaid = plan.getUnitPrice().multiply(BigDecimal.valueOf(unitCount))
                .setScale(4, RoundingMode.HALF_UP);

        // 先存定购（拿 id 作幂等键），再记账
        CapacitySubscription sub = CapacitySubscription.builder()
                .planId(planId)
                .subscriberUserId(subscriberUserId)
                .unitCount(unitCount)
                .prepaidAmount(prepaid)
                .status(CapacitySubscriptionStatus.ACTIVE)
                .build();
        sub = subscriptionRepository.save(sub);

        // 预付产能款：借 定购方账户，贷 厂家(计划方)账户 —— 资金直付厂家托管，平台不持池
        Long subAccountId = accountService.getOrCreateUserAccount(subscriberUserId).getId();
        Long ownerAccountId = accountService.getOrCreateUserAccount(plan.getOwnerUserId()).getId();
        LedgerViews.TxnResult prepaidTxn = ledgerService.postEntries(BizType.CAPACITY_SUBSCRIPTION,
                "CAPSUB-" + sub.getId(),
                List.of(
                        new LedgerRequests.Entry(subAccountId, LedgerRequests.Direction.D, prepaid, "容量预订预付"),
                        new LedgerRequests.Entry(ownerAccountId, LedgerRequests.Direction.C, prepaid, "容量预订预付(厂家托管)")
                ));
        sub.setLedgerTxnId(prepaidTxn.txnId().toString());
        subscriptionRepository.save(sub);

        // 进度计数
        plan.setSubscribedUnits(plan.getSubscribedUnits() + unitCount);
        planRepository.save(plan);

        log.info("容量定购 planId={} subscriber={} units={} prepaid={}", planId, subscriberUserId, unitCount, prepaid);
        return sub;
    }

    /**
     * 租赁完成时自动回佣：从厂家 owner_share 计提，按 unit_count/total_units 二次拆分到各定购单位。
     *
     * <p>调用方（SharedPoolService.completeRental）应捕获异常，确保回佣失败不阻断租赁完成。
     *
     * @return 本次产生的回佣明细条数（0 表示无计划/无定购/低于下限）
     */
    @Transactional
    public int applyRebateForRental(Long rentalOrderId) {
        RentalOrder order = rentalOrderRepository.findById(rentalOrderId)
                .orElseThrow(() -> BizException.notFound("error.rental.not.found"));
        BigDecimal ownerShare = order.getOwnerShare();
        if (ownerShare == null || ownerShare.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }

        CapacityPlan plan = planRepository.findFirstByAssetIdAndDeletedFalseOrderByCreatedAtDesc(order.getAssetId())
                .orElse(null);
        if (plan == null || plan.getStatus() != CapacityPlanStatus.OPEN) {
            return 0;
        }

        List<CapacitySubscription> subs = subscriptionRepository
                .findByPlanIdAndStatusAndDeletedFalse(plan.getId(), CapacitySubscriptionStatus.ACTIVE);
        if (subs.isEmpty()) {
            return 0;
        }

        CapacityRebateRule rule = rebateRuleRepository
                .findFirstByPlanIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(plan.getId(), "ACTIVE")
                .orElse(null);
        BigDecimal rebateRate = rule != null ? rule.getRebateRate() : plan.getRebateRate();
        // ⑤ 防御性夹紧：回佣率不超配置上限 CAPACITY_REBATE_RATE_MAX（兜底 0.30），防止历史脏数据越界。
        // 回佣拆分比例（unitCount/totalUnits）对 SERIAL / PARALLEL 两类语义一致。
        rebateRate = rebateRate.min(maxRebateRate());
        BigDecimal rebateTotal = ownerShare.multiply(rebateRate).setScale(4, RoundingMode.HALF_UP);
        BigDecimal minPayout = rule != null ? rule.getMinPayout() : BigDecimal.valueOf(0.01);
        if (rebateTotal.compareTo(minPayout) < 0) {
            return 0;
        }

        int totalUnits = plan.getTotalUnits();
        BigDecimal denom = BigDecimal.valueOf(totalUnits);

        // 计算每定购单位应得（先四舍五入，尾差补到第一条）
        List<BigDecimal> raw = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (CapacitySubscription s : subs) {
            BigDecimal ratio = BigDecimal.valueOf(s.getUnitCount()).divide(denom, 6, RoundingMode.HALF_UP);
            BigDecimal amt = rebateTotal.multiply(ratio).setScale(4, RoundingMode.HALF_UP);
            raw.add(amt);
            sum = sum.add(amt);
        }
        BigDecimal diff = rebateTotal.subtract(sum);
        if (!diff.equals(BigDecimal.ZERO) && !subs.isEmpty()) {
            raw.set(0, raw.get(0).add(diff));
        }

        // 复式记账：借 平台清算户(MASTER, 豁免余额校验)，贷 各定购单位用户账户
        Long clearingId = accountService.getOrCreatePlatformAccount(AccountType.MASTER).getId();
        List<LedgerRequests.Entry> entries = new ArrayList<>();
        entries.add(new LedgerRequests.Entry(clearingId, LedgerRequests.Direction.D, rebateTotal, "容量回佣计提"));
        for (int i = 0; i < subs.size(); i++) {
            Long subAccountId = accountService.getOrCreateUserAccount(subs.get(i).getSubscriberUserId()).getId();
            entries.add(new LedgerRequests.Entry(subAccountId, LedgerRequests.Direction.C, raw.get(i), "容量回佣"));
        }
        LedgerViews.TxnResult txn = ledgerService.postEntries(BizType.CAPACITY_REBATE,
                "REBATE-" + order.getOrderNo(), entries);

        // 写回佣明细
        String batchNo = "REB-" + LocalDate.now().toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        int n = 0;
        for (int i = 0; i < subs.size(); i++) {
            CapacitySubscription s = subs.get(i);
            BigDecimal ratio = BigDecimal.valueOf(s.getUnitCount()).divide(denom, 6, RoundingMode.HALF_UP);
            rebateSettlementRepository.save(CapacityRebateSettlement.builder()
                    .settlementNo(batchNo)
                    .rentalOrderId(order.getId())
                    .planId(plan.getId())
                    .poolEntryId(order.getPoolEntryId())
                    .ownerShareBase(ownerShare)
                    .rebateTotal(rebateTotal)
                    .subscriberUserId(s.getSubscriberUserId())
                    .unitCount(s.getUnitCount())
                    .ratio(ratio)
                    .amount(raw.get(i))
                    .ledgerTxnId(txn.txnId().toString())
                    .status(com.claw.server.common.enums.RebateStatus.SETTLED)
                    .build());
            n++;
        }

        log.info("容量回佣 planId={} rental={} rebateTotal={} 受益定购单位={}",
                plan.getId(), order.getOrderNo(), rebateTotal, n);
        return n;
    }

    /** 查询资产当前开放容量计划（前端入口用）。 */
    @Transactional(readOnly = true)
    public CapacityPlan findOpenPlan(Long assetId) {
        return planRepository.findFirstByAssetIdAndDeletedFalseOrderByCreatedAtDesc(assetId).orElse(null);
    }

    /**
     * 容量预订默认回佣率（配置驱动，兜底 0.10）。
     *
     * <p>取自 {@code system_config.CAPACITY_REBATE_RATE_DEFAULT}，缺失或非法时回退 0.10。
     * 与 {@code CreditLimitService.defaultMultiplier()} 同款取值方式，不硬编码默认倍率。
     */
    public BigDecimal defaultRebateRate() {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse("CAPACITY_REBATE_RATE_DEFAULT")
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> {
                    try {
                        return new BigDecimal(v.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(v -> v != null)
                .orElseGet(() -> {
                    log.warn("system_config.CAPACITY_REBATE_RATE_DEFAULT 缺失或非法，回退兜底值 0.10");
                    return new BigDecimal("0.10");
                });
    }

    /**
     * 容量预订回佣率上限（配置驱动，兜底 0.30）。
     */
    public BigDecimal maxRebateRate() {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse("CAPACITY_REBATE_RATE_MAX")
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> {
                    try {
                        return new BigDecimal(v.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(v -> v != null)
                .orElseGet(() -> {
                    log.warn("system_config.CAPACITY_REBATE_RATE_MAX 缺失或非法，回退兜底值 0.30");
                    return new BigDecimal("0.30");
                });
    }

    /** 厂家视角：列出自己发布的容量计划。 */
    @Transactional(readOnly = true)
    public List<CapacityPlan> listPlans(Long ownerUserId) {
        return planRepository.findByOwnerUserIdAndDeletedFalse(ownerUserId);
    }

    /** 用户视角：列出自己参与的定购记录。 */
    @Transactional(readOnly = true)
    public List<CapacitySubscription> listBySubscriber(Long subscriberUserId) {
        return subscriptionRepository.findBySubscriberUserIdAndDeletedFalse(subscriberUserId);
    }

    /** 用户视角：列出自己名下的回佣结算明细。 */
    @Transactional(readOnly = true)
    public List<CapacityRebateSettlement> listRebatesBySubscriber(Long subscriberUserId) {
        return rebateSettlementRepository.findBySubscriberUserIdAndDeletedFalse(subscriberUserId);
    }
}
