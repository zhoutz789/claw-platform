package com.claw.server.domain.fulfillment;

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
 * ① 去重查询（settlement 已 SETTLED/DONE 直接跳过）；
 * ② DB 唯一约束 {@code uq_fulfillment_order_id}（V87）；
 * ③ ledger 幂等键 {@code (bizType, bizRef)}（重复过账报 40950）。
 * 订单状态机 PICKED_UP → SETTLED 作为第四重护栏。
 *
 * <p><b>业务挂起返回而非抛异常</b>（遵循 OutboxHandler 约定）：提成规则缺失、收款户未绑定、
 * 订单金额非正等属于业务终局，本服务落 MANUAL 挂起记录后<b>正常返回</b>，由 OutboxRelay 标记
 * PUBLISHED（不重试、不进死信）；只有 DB/锁/序列化等技术故障才冒泡，触发重试/死信。
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

        // ---------- 金额计算（DB 为权威，payload 不参算）----------
        BigDecimal total = order.getTotalAmount() == null ? ZERO : order.getTotalAmount();
        if (total.compareTo(ZERO) <= 0) {
            // 金额非正：挂起待人工核对，绝不释放冻结、绝不过账
            suspend(order, eventId, SettlementStep.LOGISTICS, SettlementStatus.MANUAL,
                    "INVALID_AMOUNT", "订单金额非正，无法结算", total, null, null, null);
            return;
        }
        BigDecimal rate = logisticsRate();
        BigDecimal logistics = total.multiply(rate).setScale(4, HALF_UP);
        BigDecimal net = total.subtract(logistics);

        Long productId = resolveFirstProductId(orderId);
        CommissionRuleService.CommissionDetail detail =
                commissionRuleService.resolveCommissionDetail(order.getManufacturerId(), productId, net);
        if (detail.ruleId() == null) {
            // 未命中任何提成规则：挂起转人工配置，不擅自按 0 结算
            suspend(order, eventId, SettlementStep.LOGISTICS, SettlementStatus.MANUAL,
                    "COMMISSION_RULE_MISSING", "未命中任何提成规则", total, rate, null, null);
            return;
        }
        BigDecimal commission = detail.commission();
        BigDecimal balanceToMfg = total.subtract(commission).setScale(4, HALF_UP);

        Long stationUserId = resolvePrincipalUser(order.getStationId(), PrincipalType.STATION.name());
        Long mfgUserId = resolvePrincipalUser(order.getManufacturerId(), PrincipalType.MANUFACTURER.name());
        if (stationUserId == null || mfgUserId == null) {
            // 收款户未绑定：挂起转人工，冻结资金保持不动
            suspend(order, eventId, SettlementStep.LOGISTICS, SettlementStatus.MANUAL,
                    "PAYEE_ACCOUNT_MISSING", "收款户未绑定（厂家/服务站主体缺失 principal_binding）",
                    total, rate, commission, detail.ruleId());
            return;
        }

        // ---------- 资金链路（同一 REQUIRES_NEW 事务内原子提交）----------
        String settlementNo = "FS-" + orderId + "-" + System.nanoTime();

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

        // ---------- 落结算单 + 推进订单状态机 ----------
        FulfillmentSettlement settlement = FulfillmentSettlement.builder()
                .settlementNo(settlementNo)
                .fulfillmentOrderId(orderId)
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
        settlementRepository.save(settlement);

        order.setStatus(FulfillmentStatus.SETTLED);
        order.setSettledAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);

        log.info("履约结算完成 orderId={} settlementNo={} total={} commission={} balanceToMfg={} ledger={}",
                orderId, settlementNo, total, commission, balanceToMfg, bal.txnId());
    }

    /* ----------------------------- 业务挂起 ----------------------------- */

    /**
     * 业务挂起：落一条 MANUAL 结算单（冻结资金保持不动，等人工介入），随后正常返回。
     * 注意：本方法在 {@link #handle} 的 REQUIRES_NEW 事务内被调用，save 会随主事务一起提交。
     */
    private void suspend(FulfillmentOrder order, long eventId, SettlementStep step, SettlementStatus status,
                         String reasonCode, String failReason, BigDecimal total, BigDecimal rate,
                         BigDecimal commission, Long ruleId) {
        FulfillmentSettlement s = FulfillmentSettlement.builder()
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
                .status(status)
                .reasonCode(reasonCode)
                .failReason(failReason)
                .handledAt(Instant.now())
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        settlementRepository.save(s);
        log.warn("履约结算挂起 orderId={} reasonCode={} detail={}", order.getId(), reasonCode, failReason);
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
}
