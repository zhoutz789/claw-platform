package com.claw.server.domain.fulfillment;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.FulfillmentStatus;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.common.enums.SettlementStatus;
import com.claw.server.common.enums.SettlementStep;
import com.claw.server.common.event.OutboxHandler;
import com.claw.server.domain.commission.CommissionRuleService;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.role.PrincipalBinding;
import com.claw.server.domain.role.PrincipalBindingRepository;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 履约异步结算服务（R7 核心，V87/V88/V89 配套）。
 *
 * <p>实现 {@link OutboxHandler}，在 {@code FulfillmentService.pickupScan} 写出的
 * {@code PICKUP_COMPLETED} 事件被 {@link com.claw.server.common.event.OutboxRelay} 认领后，
 * 由本服务在独立 {@code REQUIRES_NEW} 事务内同步完成整条资金链路：
 * <ol>
 *   <li>释放用户 SUB 账户冻结（frozen −= total，与 {@code FulfillmentService.freezeFunds} 对称）；</li>
 *   <li>平台内部户 → 服务站 MASTER：服务站提成（commission）；</li>
 *   <li>平台内部户 → 厂家 MASTER：厂家货款（balance_to_mfg = total − commission）。</li>
 * </ol>
 * 全局借贷守恒：用户 −total、平台 0、服务站 +commission、厂家 +balance_to_mfg；
 * 实际仅两笔出账（提成 + 货款），物流费「先不分配、留在厂家货款里」只作台账 memo。
 *
 * <p><b>幂等（最重要）</b>：同一 {@code fulfillment_order_id} 只能成功结算一次，三重兜底——
 * ① 去重查询（settlement 已存在直接跳过）；
 * ② DB 唯一约束 {@code uq_fulfillment_order_id}（V87）；
 * ③ ledger 幂等键 {@code (bizType, bizRef)}（重复过账报 40950）。
 * 订单状态机 PICKED_UP → SETTLED 作为第四重护栏。
 *
 * <p><b>业务挂起返回而非抛异常</b>（遵循 OutboxHandler 约定）：提成规则缺失、收款户未绑定、
 * 订单金额非正等属于业务终局，本服务落 MANUAL 挂起记录后<b>正常返回</b>，由 OutboxRelay 标记
 * PUBLISHED（不重试、不进死信）；只有 DB/锁/序列化等技术故障才冒泡，触发重试/死信。
 *
 * <p><b>人工介入（老板「失败挂起转人工」承诺）</b>：MANUAL/FAILED 挂起单由后台管理员经
 * {@code AdminFulfillmentController} 触发 {@link #retry}（修复根因后重跑完整资金链路，回填既有行）
 * 或 {@link #resolve}（行政关闭、释放用户冻结、置 DONE）。二者与 {@link #handle} 共用
 * {@link #computeAndMove} 这一唯一结算实现，确保「自动 / 手动」两套路径金额口径完全一致。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FulfillmentSettlementService implements OutboxHandler {

    private final FulfillmentOrderRepository orderRepository;
    private final FulfillmentOrderItemRepository orderItemRepository;
    private final FulfillmentSettlementRepository settlementRepository;
    /** 跨域资金交互唯一出口（ArchUnit：资金域仓储不得跨域访问）。 */
    private final AccountService accountService;
    /** 复式记账引擎（任何 balance 变动必须经它，禁止直接改 Account.balance）。 */
    private final LedgerService ledgerService;
    private final CommissionRuleService commissionRuleService;
    private final PrincipalBindingRepository bindingRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper;

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final RoundingMode HALF_UP = RoundingMode.HALF_UP;

    @Override
    public String eventType() {
        return "PICKUP_COMPLETED";
    }

    /**
     * 处理一条取货扫码完成事件：完成整条资金结算链路。
     *
     * <p>独立 {@code REQUIRES_NEW} 事务——与 OutboxRelay 的终态化事务分离；批内其它事件失败不影响本事务。
     * 整段逻辑（释放冻结 + 三笔过账 + 结算单落库 + 订单置 SETTLED）在同一事务内原子提交或整体回滚。
     * 技术异常冒泡由 relay 决定重试/死信；业务挂起正常返回（落 MANUAL）。
     *
     * @param eventId     outbox 事件 id（作 source_event_id 溯源）
     * @param payloadJson 事件载荷 JSON（仅取 orderId 定位订单；金额一律以 DB 为权威）
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(long eventId, String payloadJson) {
        Long orderId = parseOrderId(payloadJson);
        if (orderId == null) {
            log.error("PICKUP_COMPLETED 事件解析失败，放弃处理 eventId={}", eventId);
            return;
        }

        FulfillmentOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("履约订单不存在，跳过结算 eventId={} orderId={}", eventId, orderId);
            return;
        }
        // 幂等 / 状态护栏：已结算直接跳过；非 PICKED_UP（含已挂起、已取消等）无法结算，消费但不报错
        if (order.getStatus() == FulfillmentStatus.SETTLED) {
            log.debug("订单已结算，幂等跳过 orderId={}", orderId);
            return;
        }
        if (order.getStatus() != FulfillmentStatus.PICKED_UP) {
            log.warn("订单非 PICKED_UP 状态，无法结算 orderId={} status={}", orderId, order.getStatus());
            return;
        }
        FulfillmentSettlement existing = settlementRepository.findByFulfillmentOrderId(orderId).orElse(null);
        if (existing != null) {
            // ★幂等：只要已存在结算单（无论 SETTLED/DONE/MANUAL/FAILED）就跳过，绝不重复处理。
            // 此前只拦 SETTLED/DONE，导致 MANUAL 挂起事件在 at-least-once 重投窗口内会再次 suspend，
            // 撞 UNIQUE(fulfillment_order_id) → 技术异常 → 重试风暴误进死信。
            // MANUAL/FAILED 的重结算由人工重投接口负责（先重置/删除本条再重投），此处不再重复落单。
            log.debug("结算单已存在，幂等跳过 orderId={} status={}", orderId, existing.getStatus());
            return;
        }

        // ---------- 核心结算（算金额 + 动钱，结果落在 out 上，本方法只负责落库）----------
        SettleOutcome out = computeAndMove(order, eventId);
        if (out.settlement() != null) {
            // 成功：持久化结算单（saveAndFlush 使并发双结的 UNIQUE 冲突在此立即抛出、可被捕获）
            try {
                settlementRepository.saveAndFlush(out.settlement());
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 并发双结：另一事务已插入同一 fulfillment_order_id 并提交。本事务已 rollback-only，
                // 前面 3 笔记账一并回滚，产生零副作用。幂等跳过、正常返回（不抛），relay 标记 PUBLISHED。
                log.warn("并发结算冲突（UNIQUE fulfillment_order_id），本事务幂等回滚 orderId={}", orderId);
                return;
            }
            order.setStatus(FulfillmentStatus.SETTLED);
            order.setSettledAt(Instant.now());
            order.setUpdatedAt(Instant.now());
            orderRepository.save(order);
            log.info("履约结算完成 orderId={} settlementNo={} total={} commission={} balanceToMfg={} ledger={}",
                    orderId, out.settlement().getSettlementNo(), out.settlement().getTotalAmount(),
                    out.settlement().getCommissionAmount(), out.settlement().getBalanceToMfg(),
                    out.settlement().getLedgerTxnId());
        } else {
            // 业务挂起：落一条 MANUAL 结算单（冻结资金保持不动，等人工介入），随后正常返回
            settlementRepository.save(out.suspended());
            log.warn("履约结算挂起 orderId={} reasonCode={} detail={}", orderId,
                    out.suspended().getReasonCode(), out.suspended().getFailReason());
        }
    }

    /* ----------------------------- 核心结算（自动 / 手动共用） ----------------------------- */

    /**
     * 核心结算：在调用方事务内算金额 + 动钱（释放冻结 + 两笔出账），但不持久化结算单。
     *
     * <p>返回成功结算单（已构建、未保存，含 ledger_txn_id）或挂起结算单（已构建、未保存）。
     * 由调用方负责落库与订单状态推进，从而让 {@link #handle}（自动）与 {@link #retry}（手动）
     * 复用同一套金额 / 记账逻辑，杜绝两套口径。
     *
     * <p><b>资金安全</b>：本方法只在成功分支真正过账；挂起分支只构建记录、绝不触碰任何账户余额。
     * 因此无论被自动或手动调用，重复进入挂起分支都不会产生任何资金副作用。
     */
    private SettleOutcome computeAndMove(FulfillmentOrder order, long eventId) {
        // ---------- 金额计算（DB 为权威，payload 不参算）----------
        BigDecimal total = order.getTotalAmount() == null ? ZERO : order.getTotalAmount();
        if (total.compareTo(ZERO) <= 0) {
            // 金额非正：挂起待人工核对，绝不释放冻结、绝不过账
            return SettleOutcome.suspended(buildSuspended(order, eventId, SettlementStep.LOGISTICS,
                    "INVALID_AMOUNT", "订单金额非正，无法结算", total, null, null, null));
        }
        BigDecimal rate = logisticsRate();
        BigDecimal logistics = total.multiply(rate).setScale(4, HALF_UP);
        BigDecimal net = total.subtract(logistics);

        Long productId = resolveFirstProductId(order.getId());
        CommissionRuleService.CommissionDetail detail =
                commissionRuleService.resolveCommissionDetail(order.getManufacturerId(), productId, net);
        if (detail.ruleId() == null) {
            // 未命中任何提成规则：挂起转人工配置，不擅自按 0 结算
            return SettleOutcome.suspended(buildSuspended(order, eventId, SettlementStep.LOGISTICS,
                    "COMMISSION_RULE_MISSING", "未命中任何提成规则", total, rate, null, null));
        }
        BigDecimal commission = detail.commission();
        BigDecimal balanceToMfg = total.subtract(commission).setScale(4, HALF_UP);

        Long stationUserId = resolvePrincipalUser(order.getStationId(), PrincipalType.STATION.name());
        Long mfgUserId = resolvePrincipalUser(order.getManufacturerId(), PrincipalType.MANUFACTURER.name());
        if (stationUserId == null || mfgUserId == null) {
            // 收款户未绑定：挂起转人工，冻结资金保持不动
            return SettleOutcome.suspended(buildSuspended(order, eventId, SettlementStep.LOGISTICS,
                    "PAYEE_ACCOUNT_MISSING", "收款户未绑定（厂家/服务站主体缺失 principal_binding）",
                    total, rate, commission, detail.ruleId()));
        }

        // ---------- 资金链路（同一 REQUIRES_NEW 事务内原子提交）----------
        String settlementNo = "FS-" + order.getId() + "-" + System.nanoTime();

        // ① 释放用户冻结：SUB 借 total，平台内部户贷 total（与 freezeFunds 对称）
        Account custSub = accountService.getOrCreateSubAccount(order.getCustomerUserId(), AccountType.SUB);
        custSub.setFrozen(nz(custSub.getFrozen()).subtract(total).max(ZERO));
        accountService.saveAccount(custSub);
        Account platform = accountService.getOrCreatePlatformAccount(AccountType.MASTER);
        ledgerService.postEntries(BizType.FULFILLMENT_SETTLEMENT, settlementNo + ":RELEASE", List.of(
                new LedgerRequests.Entry(custSub.getId(), LedgerRequests.Direction.D, total, "履约结算释放冻结"),
                new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.C, total, "履约结算释放冻结")));

        // ② 服务站提成：平台内部户借 commission，服务站 MASTER 贷 commission
        Account stationMaster = ensureCurrency(accountService.getOrCreateUserAccount(stationUserId));
        ledgerService.postEntries(BizType.FULFILLMENT_SETTLEMENT, settlementNo + ":COMMISSION", List.of(
                new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.D, commission, "服务站提成"),
                new LedgerRequests.Entry(stationMaster.getId(), LedgerRequests.Direction.C, commission, "服务站提成")));

        // ③ 厂家货款：平台内部户借 balance_to_mfg，厂家 MASTER 贷 balance_to_mfg
        Account mfgMaster = ensureCurrency(accountService.getOrCreateUserAccount(mfgUserId));
        LedgerViews.TxnResult bal = ledgerService.postEntries(BizType.FULFILLMENT_SETTLEMENT, settlementNo + ":BALANCE", List.of(
                new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.D, balanceToMfg, "厂家货款"),
                new LedgerRequests.Entry(mfgMaster.getId(), LedgerRequests.Direction.C, balanceToMfg, "厂家货款")));

        // ---------- 构建结算单（不落库，交给调用方）----------
        FulfillmentSettlement settlement = FulfillmentSettlement.builder()
                .settlementNo(settlementNo)
                .fulfillmentOrderId(order.getId())
                .manufacturerId(order.getManufacturerId())
                .stationId(order.getStationId())
                .logisticsFee(logistics)
                .commissionAmount(commission)
                .balanceToMfg(balanceToMfg)
                .totalAmount(total)
                .logisticsFeeRate(rate)
                .commissionRuleId(detail.ruleId())
                .commissionRuleSnapshot(detail.ruleSnapshot())
                .customerUserId(order.getCustomerUserId())
                .currency("USD")
                .sourceEventId(eventId)
                .step(SettlementStep.BALANCE)
                .status(SettlementStatus.SETTLED)
                .ledgerTxnId(bal.txnId().toString())
                .settledAt(Instant.now())
                .handledAt(Instant.now())
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return SettleOutcome.success(settlement);
    }

    /** 构建一条 MANUAL 挂起结算单（不落库）。 */
    private FulfillmentSettlement buildSuspended(FulfillmentOrder order, long eventId, SettlementStep step,
                                                 String reasonCode, String failReason, BigDecimal total,
                                                 BigDecimal rate, BigDecimal commission, Long ruleId) {
        return FulfillmentSettlement.builder()
                .settlementNo("FS-" + order.getId() + "-" + System.nanoTime())
                .fulfillmentOrderId(order.getId())
                .manufacturerId(order.getManufacturerId())
                .stationId(order.getStationId())
                .totalAmount(total)
                .logisticsFeeRate(rate)
                .commissionAmount(commission)
                .commissionRuleId(ruleId)
                .customerUserId(order.getCustomerUserId())
                .currency("USD")
                .sourceEventId(eventId)
                .step(step)
                .status(SettlementStatus.MANUAL)
                .reasonCode(reasonCode)
                .failReason(failReason)
                .handledAt(Instant.now())
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    /* ----------------------------- 人工介入 ----------------------------- */

    /**
     * 人工重结算：针对 MANUAL/FAILED 挂起单，修复根因（绑定收款户 / 配置提成规则 / 校正金额）后，
     * 重新跑完整资金链路（释放冻结 + 提成 + 货款），并把结果<b>回填到既有行</b>
     * （保持「一订单一行」审计约束，不新建行）。
     *
     * <p><b>资金安全</b>：MANUAL 挂起单从未动过钱，此处首次过账即为唯一一次，绝不会双结双付。
     * 并发双结由 {@code findByIdForUpdate} 悲观锁订单串行化；若订单已被其它路径置 SETTLED，则幂等同步后返回。
     *
     * @param settlementId 挂起结算单 id
     * @param operatorId   操作管理员 userId（作 handled_by 审计）
     * @param remark       处理备注
     * @return 处理后的结算单（SETTLED 或仍 MANUAL）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FulfillmentSettlement retry(Long settlementId, Long operatorId, String remark) {
        FulfillmentSettlement s = settlementRepository.findById(settlementId).orElse(null);
        if (s == null) {
            throw BizException.of(40401, "settlement.not.found");
        }
        if (s.getStatus() != SettlementStatus.MANUAL && s.getStatus() != SettlementStatus.FAILED) {
            throw BizException.of(40951, "settlement.not.pending", s.getStatus());
        }
        // 悲观锁订单，串行化并发重结算（防止双结双付）
        FulfillmentOrder order = orderRepository.findByIdForUpdate(s.getFulfillmentOrderId()).orElse(null);
        if (order == null) {
            // 订单已删：挂单行直接置 DONE（MANUAL 从未动钱，无资金副作用）
            s.setStatus(SettlementStatus.DONE);
            s.setHandledBy(operatorId);
            s.setHandledAt(Instant.now());
            s.setHandleRemark(remark);
            s.setRetryCount(s.getRetryCount() + 1);
            s.setUpdatedAt(Instant.now());
            return settlementRepository.save(s);
        }
        if (order.getStatus() == FulfillmentStatus.SETTLED) {
            // 已被其它路径结算：幂等同步行状态后返回，避免重复过账
            s.setStatus(SettlementStatus.SETTLED);
            s.setHandledBy(operatorId);
            s.setHandledAt(Instant.now());
            s.setHandleRemark(remark);
            s.setRetryCount(s.getRetryCount() + 1);
            s.setUpdatedAt(Instant.now());
            return settlementRepository.save(s);
        }
        if (order.getStatus() != FulfillmentStatus.PICKED_UP) {
            throw BizException.of(40952, "settlement.order.not.pickup", order.getStatus());
        }

        long eventId = s.getSourceEventId() != null ? s.getSourceEventId() : -settlementId;
        SettleOutcome out = computeAndMove(order, eventId);
        s.setRetryCount(s.getRetryCount() + 1);
        s.setHandledBy(operatorId);
        s.setHandledAt(Instant.now());
        s.setHandleRemark(remark);
        s.setUpdatedAt(Instant.now());

        if (out.settlement() != null) {
            // 成功：把计算结果回填到既有行（保持一行一单 + 审计）
            FulfillmentSettlement ok = out.settlement();
            s.setSettlementNo(ok.getSettlementNo());
            s.setLogisticsFee(ok.getLogisticsFee());
            s.setCommissionAmount(ok.getCommissionAmount());
            s.setBalanceToMfg(ok.getBalanceToMfg());
            s.setTotalAmount(ok.getTotalAmount());
            s.setLogisticsFeeRate(ok.getLogisticsFeeRate());
            s.setCommissionRuleId(ok.getCommissionRuleId());
            s.setCommissionRuleSnapshot(ok.getCommissionRuleSnapshot());
            s.setStep(ok.getStep());
            s.setStatus(SettlementStatus.SETTLED);
            s.setLedgerTxnId(ok.getLedgerTxnId());
            s.setSettledAt(ok.getSettledAt());
            s.setReasonCode(null);
            s.setFailReason(null);
            order.setStatus(FulfillmentStatus.SETTLED);
            order.setSettledAt(Instant.now());
            order.setUpdatedAt(Instant.now());
            orderRepository.save(order);
            log.info("人工重结算成功 settlementId={} orderId={} settlementNo={}",
                    settlementId, order.getId(), ok.getSettlementNo());
        } else {
            // 根因仍未修复：更新原因，保持 MANUAL，等待下一次修复
            FulfillmentSettlement man = out.suspended();
            s.setStep(man.getStep());
            s.setReasonCode(man.getReasonCode());
            s.setFailReason(man.getFailReason());
            log.warn("人工重结算仍挂起 settlementId={} orderId={} reasonCode={}",
                    settlementId, order.getId(), man.getReasonCode());
        }
        return settlementRepository.save(s);
    }

    /**
     * 人工置为已处理（行政关闭，不结算）：释放用户冻结资金回可用余额（不给付服务站/厂家），
     * 结算单置 DONE。适用于「根因无法修复、订单终止 / 退款给用户」的场景。
     *
     * <p><b>资金安全</b>：仅做冻结释放（用户余额回冲），不动服务站/厂家账户，不产生任何「虚付」。
     * 注意：<b>不得</b>把订单置 CANCELLED —— {@code FulfillmentService.cancel} 守卫禁止
     * PICKED_UP → CANCELLED（抛 40919）；订单保持 PICKED_UP，由 DONE 结算单记录本次处置。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FulfillmentSettlement resolve(Long settlementId, Long operatorId, String remark) {
        FulfillmentSettlement s = settlementRepository.findById(settlementId).orElse(null);
        if (s == null) {
            throw BizException.of(40401, "settlement.not.found");
        }
        if (s.getStatus() != SettlementStatus.MANUAL && s.getStatus() != SettlementStatus.FAILED) {
            throw BizException.of(40951, "settlement.not.pending", s.getStatus());
        }
        FulfillmentOrder order = orderRepository.findByIdForUpdate(s.getFulfillmentOrderId()).orElse(null);
        if (order != null && order.getStatus() == FulfillmentStatus.SETTLED) {
            // 已被其它路径结算：幂等同步后返回
            s.setStatus(SettlementStatus.SETTLED);
            s.setHandledBy(operatorId);
            s.setHandledAt(Instant.now());
            s.setHandleRemark(remark);
            s.setRetryCount(s.getRetryCount() + 1);
            s.setUpdatedAt(Instant.now());
            return settlementRepository.save(s);
        }
        // 行政退款：释放用户冻结资金回可用余额（不结算给服务站/厂家）
        if (order != null) {
            BigDecimal amt = nz(order.getFrozenAmount());
            if (amt.compareTo(ZERO) > 0) {
                Account custSub = accountService.getOrCreateSubAccount(order.getCustomerUserId(), AccountType.SUB);
                custSub.setFrozen(nz(custSub.getFrozen()).subtract(amt).max(ZERO));
                accountService.saveAccount(custSub);
                Account platform = accountService.getOrCreatePlatformAccount(AccountType.MASTER);
                ledgerService.postEntries(BizType.REFUND,
                        "FS-RESOLVE-" + order.getId() + "-" + System.nanoTime() + ":RELEASE", List.of(
                        new LedgerRequests.Entry(custSub.getId(), LedgerRequests.Direction.D, amt, "履约结算挂起行政退款"),
                        new LedgerRequests.Entry(platform.getId(), LedgerRequests.Direction.C, amt, "履约结算挂起行政退款")));
            }
        }
        s.setStatus(SettlementStatus.DONE);
        s.setHandledBy(operatorId);
        s.setHandledAt(Instant.now());
        s.setHandleRemark(remark);
        s.setRetryCount(s.getRetryCount() + 1);
        s.setUpdatedAt(Instant.now());
        log.info("人工置已处理（行政关闭）settlementId={} orderId={}", settlementId,
                s.getFulfillmentOrderId());
        return settlementRepository.save(s);
    }

    /** 列出挂起 / 失败结算单（后台「待人工处理」看板）。 */
    @Transactional(readOnly = true)
    public List<FulfillmentSettlement> listSettlements(List<SettlementStatus> statuses, Long orderId) {
        if (orderId != null) {
            return settlementRepository.findByFulfillmentOrderId(orderId)
                    .map(java.util.Collections::singletonList)
                    .orElse(java.util.Collections.emptyList());
        }
        List<SettlementStatus> q = (statuses == null || statuses.isEmpty())
                ? List.of(SettlementStatus.MANUAL, SettlementStatus.FAILED)
                : statuses;
        return settlementRepository.findByStatusIn(q);
    }

    /** 结算单详情（含失败原因、处理人）。 */
    @Transactional(readOnly = true)
    public FulfillmentSettlement getSettlement(Long id) {
        return settlementRepository.findById(id)
                .orElseThrow(() -> BizException.of(40401, "settlement.not.found"));
    }

    /* ----------------------------- 内部工具 ----------------------------- */

    private Long parseOrderId(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            JsonNode o = node.get("orderId");
            if (o == null || o.isNull()) {
                return null;
            }
            return o.asLong();
        } catch (Exception e) {
            log.error("解析 PICKUP_COMPLETED payload 失败", e);
            return null;
        }
    }

    private Long resolveFirstProductId(Long orderId) {
        List<FulfillmentOrderItem> items = orderItemRepository.findByFulfillmentOrderId(orderId);
        if (items != null && !items.isEmpty()) {
            return items.get(0).getProductId();
        }
        return null;
    }

    /** 反向查收款户用户 id（复用 FulfillmentService.resolvePrincipalUser 口径）。 */
    private Long resolvePrincipalUser(Long principalId, String principalType) {
        if (principalId == null) {
            return null;
        }
        List<PrincipalBinding> b = bindingRepository.findByPrincipalTypeAndPrincipalId(principalType, principalId);
        return b.isEmpty() ? null : b.get(0).getUserId();
    }

    /**
     * 物流费率：取 system_config.STATION_LOGISTICS_FEE_RATE（默认 0，厂家承担）。
     * 非法（负数 / 非数字）→ 按 0 处理并 {@code log.warn}（老板明确：非 MANUAL，按 0 继续结算）。
     */
    private BigDecimal logisticsRate() {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse("STATION_LOGISTICS_FEE_RATE")
                .map(c -> {
                    try {
                        BigDecimal r = new BigDecimal(c.getConfigValue());
                        if (r.compareTo(ZERO) < 0) {
                            log.warn("物流费率为负数，按 0 处理 key=STATION_LOGISTICS_FEE_RATE value={}",
                                    c.getConfigValue());
                            return ZERO;
                        }
                        return r;
                    } catch (NumberFormatException e) {
                        log.warn("物流费率非法（非数字），按 0 处理 key=STATION_LOGISTICS_FEE_RATE value={}",
                                c.getConfigValue());
                        return ZERO;
                    }
                })
                .orElse(ZERO);
    }

    /** 用户主账户可能由上游懒建且未带 currency，补默认值避免下游按币种检索错位。 */
    private Account ensureCurrency(Account a) {
        if (a.getCurrency() == null) {
            a.setCurrency("USD");
            accountService.saveAccount(a);
        }
        return a;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    /** 结算结果载体：成功（settlement 非空）或挂起（suspended 非空），二者互斥。 */
    private static final class SettleOutcome {
        private final FulfillmentSettlement settlement;
        private final FulfillmentSettlement suspended;

        private SettleOutcome(FulfillmentSettlement settlement, FulfillmentSettlement suspended) {
            this.settlement = settlement;
            this.suspended = suspended;
        }

        static SettleOutcome success(FulfillmentSettlement s) {
            return new SettleOutcome(s, null);
        }

        static SettleOutcome suspended(FulfillmentSettlement s) {
            return new SettleOutcome(null, s);
        }

        FulfillmentSettlement settlement() {
            return settlement;
        }

        FulfillmentSettlement suspended() {
            return suspended;
        }
    }
}
