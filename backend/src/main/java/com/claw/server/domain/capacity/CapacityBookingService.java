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
import com.claw.server.domain.manufacturer.Product;
import com.claw.server.domain.manufacturer.ProductRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.claw.server.domain.sharedpool.RentalOrder;
import com.claw.server.domain.sharedpool.RentalOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
    /** 商品仓储（跨域只读校验：建计划前确认 productId 指向的商品真实存在）。 */
    private final ProductRepository productRepository;

    private static final BigDecimal SCALE4 = BigDecimal.valueOf(4);

    /**
     * 发布容量预订计划（V81 简化入参：挂商品 + 份数 + 单价 + 窗口 + 计划说明）。
     *
     * <p>相比 V71 的 9 字段全手填版本，本方法只收「使用者必须且只能自己决定」的参数：
     * <ul>
     *   <li>{@code ownerUserId} 由调用方从登录态带出（不再是请求体字段），杜绝冒用他人身份建计划；</li>
     *   <li>{@code capacityType} 固定 {@link CapacityType#PARALLEL}（并行共享额度；
     *       V84 起重复预定走「一订户一行、累加」而非多行 top-up，见 {@link #subscribe}）；</li>
     *   <li>{@code rebateRate} 不再入参，统一取 system_config 的 CAPACITY_REBATE_RATE_DEFAULT
     *       并夹紧到 CAPACITY_REBATE_RATE_MAX，保证同类计划口径一致；</li>
     *   <li>{@code assetId} / {@code poolEntryId} 不再入参（V81 已置空列可空）。</li>
     * </ul>
     *
     * @param productId   关联商品（必填，前端「容量预定」按钮联动带入，不可手改；
     *                    V83 起校验该商品存在且未软删，不存在则 40401 product.not.found）
     * @param ownerUserId 计划发布方（厂家），取自登录态
     * @param totalUnits  总容量单位数（必填，&gt; 0）
     * @param unitPrice   每单位产能预付款（必填，&gt; 0）
     * @param windowStart 预订窗口起（选填）
     * @param windowEnd   预订窗口止（选填）
     * @param planDesc    计划说明（风险提示 + 操作方法，必填，客户侧只读展示）
     */
    @Transactional
    public CapacityPlan createPlan(Long productId, Long ownerUserId, Integer totalUnits,
                                   BigDecimal unitPrice, Instant windowStart, Instant windowEnd,
                                   String planDesc) {
        if (productId == null) {
            throw BizException.invalidParam("error.capacity.product.required");
        }
        if (ownerUserId == null) {
            throw BizException.invalidParam("error.capacity.owner.required");
        }
        if (totalUnits == null || totalUnits <= 0) {
            throw BizException.invalidParam("error.capacity.units.invalid");
        }
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.invalidParam("error.capacity.price.invalid");
        }
        if (planDesc == null || planDesc.isBlank()) {
            throw BizException.invalidParam("error.capacity.desc.required");
        }
        // 商品必须真实存在（V83）：productId 由前端「容量预定」按钮联动带入，但仍是外部输入，
        // 不校验会允许把容量计划挂到一个不存在的商品上，客户侧打开即空数据且无法追溯。
        // deleted = TRUE 的软删商品同样视为不存在，避免复活已下架商品。
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null || Boolean.TRUE.equals(product.getDeleted())) {
            throw BizException.of(40401, "product.not.found");
        }

        // 回佣率不再由前端传：统一取配置默认（CAPACITY_REBATE_RATE_DEFAULT，兜底 0.10），
        // 并夹紧到上限（CAPACITY_REBATE_RATE_MAX，兜底 0.30），防止配置被改大后越界。
        BigDecimal effectiveRebateRate = defaultRebateRate().min(maxRebateRate());

        CapacityPlan plan = CapacityPlan.builder()
                .productId(productId)
                .ownerUserId(ownerUserId)
                .totalUnits(totalUnits)
                .subscribedUnits(0)
                .unitPrice(unitPrice)
                .planDesc(planDesc.trim())
                .capacityType(CapacityType.PARALLEL)
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

        log.info("发布容量预订计划 planId={} productId={} totalUnits={} owner={}",
                plan.getId(), productId, totalUnits, ownerUserId);
        return plan;
    }

    /**
     * 用户定购容量单位：预付产能款直付厂家托管（平台不经手资金池）。
     *
     * <p><b>V84 语义订正：一订户一行，重复预定累加。</b>
     * V71 建的部分唯一索引 {@code uq_cap_sub_active}
     * （{@code UNIQUE (plan_id, subscriber_user_id) WHERE deleted = FALSE AND status = 'ACTIVE'}）
     * 已经强制「同一订户在同一计划下至多一行 ACTIVE 定购」，所以同一订户再次预定时应当
     * <b>累加到既有那一行</b>（unit_count / prepaid_amount 相加），而不是再插一行 ——
     * 后者必然撞唯一索引，此前正是因此直接抛裸 500 并把 PSQLException 细节吐给了前端。
     * 此处选择改代码而不是改索引：索引已在演示/生产库生效，改索引的迁移风险远大于改
     * 写入路径；且「我的预订」列表按「一订户一行」展示也更符合用户预期。
     *
     * <p>金额口径：重复预定时本次记账的金额是<b>本次新增份数对应的预付款</b>
     * （unit_price × 本次 unit_count），<b>不是</b>累加后的总额 —— 总额里属于上一次的
     * 部分早已在上一次记账时划转给厂家，再划一次就是双重扣款。
     *
     * @return 定购记录（首次预定=新增行；重复预定=累加后的<b>同一行</b>）
     * @throws BizException 40973 error.capacity.subscription.duplicate（并发下撞唯一索引，HTTP 409）
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
        // ④ CapacityType 语义分支（V84 订正）：
        // SERIAL（串行独占）：至多一个定购行，重复定购直接拒绝（40971）—— 串行容量是排他的
        //   优先权，加份数没有业务含义，故不累加；
        // PARALLEL（并行共享）：一订户一行，重复定购累加到既有 ACTIVE 行（不再允许多行
        //   top-up —— 多行会撞 uq_cap_sub_active 抛 DataIntegrityViolationException，必须避免），
        //   且单订户累计份数不得超总容量（40972）。
        CapacitySubscription existing = null;
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
            // 定位既有 ACTIVE 行：命中 → 累加；未命中 → 新增。
            existing = subscriptionRepository
                    .findByPlanIdAndSubscriberUserIdAndStatusAndDeletedFalse(
                            planId, subscriberUserId, CapacitySubscriptionStatus.ACTIVE)
                    .orElse(null);
        }

        // 本次新增份数对应的预付款（重复预定时也只是增量，不含历史已付部分）
        BigDecimal prepaid = plan.getUnitPrice().multiply(BigDecimal.valueOf(unitCount))
                .setScale(4, RoundingMode.HALF_UP);

        try {
            CapacitySubscription sub;
            if (existing != null) {
                // ---- 重复预定：累加到既有行，不 insert（insert 必然撞 uq_cap_sub_active）----
                sub = existing;
                int baseUnits = sub.getUnitCount() == null ? 0 : sub.getUnitCount();
                BigDecimal basePrepaid = sub.getPrepaidAmount() == null ? BigDecimal.ZERO : sub.getPrepaidAmount();
                sub.setUnitCount(baseUnits + unitCount);
                sub.setPrepaidAmount(basePrepaid.add(prepaid));
            } else {
                // ---- 首次预定：新增一行（先存拿 id 作记账幂等键）----
                sub = subscriptionRepository.save(CapacitySubscription.builder()
                        .planId(planId)
                        .subscriberUserId(subscriberUserId)
                        .unitCount(unitCount)
                        .prepaidAmount(prepaid)
                        .status(CapacitySubscriptionStatus.ACTIVE)
                        .build());
            }

            // 预付产能款：借 定购方账户，贷 厂家(计划方)账户 —— 资金直付厂家托管，平台不持池。
            // 金额恒为本笔增量 prepaid：历史部分已在上次记账时划转，不得重复划转。
            Long subAccountId = accountService.getOrCreateUserAccount(subscriberUserId).getId();
            Long ownerAccountId = accountService.getOrCreateUserAccount(plan.getOwnerUserId()).getId();
            // 记账幂等键必须<b>每笔付款唯一</b>：一订户一行后，重复预定复用同一 sub.getId()，
            // 若只用 "CAPSUB-{subId}" 会被账本的幂等保护判为重复过账（40950）而付不了第二次款。
            // 这里拼上「本次过账后的累计份数」——它随每次预定严格递增，既唯一又可重放。
            LedgerViews.TxnResult prepaidTxn = ledgerService.postEntries(BizType.CAPACITY_SUBSCRIPTION,
                    "CAPSUB-" + sub.getId() + "-" + sub.getUnitCount(),
                    List.of(
                            new LedgerRequests.Entry(subAccountId, LedgerRequests.Direction.D, prepaid, "容量预订预付"),
                            new LedgerRequests.Entry(ownerAccountId, LedgerRequests.Direction.C, prepaid, "容量预订预付(厂家托管)")
                    ));
            sub.setLedgerTxnId(prepaidTxn.txnId().toString());
            // V81：付款即完成（无独立收银台），记账成功后打付款时间戳。
            sub.setPaidAt(Instant.now());
            sub.setUpdatedAt(Instant.now());
            subscriptionRepository.save(sub);

            // 进度计数：只加本次增量（不是累加后的总份数，否则历史份数会被重复计入）
            plan.setSubscribedUnits(plan.getSubscribedUnits() + unitCount);
            planRepository.save(plan);

            log.info("容量定购 planId={} subscriber={} units={} prepaid={} 累加={} 累加后份数={}",
                    planId, subscriberUserId, unitCount, prepaid, existing != null, sub.getUnitCount());
            return sub;
        } catch (DataIntegrityViolationException e) {
            // 并发下两个请求同时判定「无既有行」并走到 insert，必有一个撞 uq_cap_sub_active。
            // 这里必须拦成 4xx：① 绝不允许裸 500（污染告警、看起来像服务端炸了）；
            // ② 绝不能把 e.getMessage() 透出去 —— 它含约束名与字段值（库结构细节，属信息外泄）。
            // BizException 是 RuntimeException，抛出后 Spring 会把整个事务回滚，撞索引的
            // 脏 session 不会被提交，这正是想要的行为。
            log.warn("容量定购撞唯一约束（并发重复预定）planId={} subscriber={} 已拒：{}",
                    planId, subscriberUserId, e.getClass().getSimpleName());
            throw BizException.of(40973, "error.capacity.subscription.duplicate");
        }
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

    /**
     * V81：按商品查容量计划（前端「容量预定」按钮按 productId 拉取，取第一条展示）。
     *
     * @return 该商品下的容量计划（按 id 升序，通常 0 或 1 条）；无计划时返回空列表
     */
    @Transactional(readOnly = true)
    public List<CapacityPlan> listPlansByProduct(Long productId) {
        if (productId == null) {
            return List.of();
        }
        return planRepository.findByProductIdAndDeletedFalse(productId);
    }

    /**
     * V81：某容量计划下的预定订单列表（厂家侧只读表格：谁、订了几份、付了多少、何时付）。
     */
    @Transactional(readOnly = true)
    public List<CapacitySubscription> listSubscriptionsByPlan(Long planId) {
        if (planId == null) {
            return List.of();
        }
        return subscriptionRepository.findByPlanIdAndDeletedFalse(planId);
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
