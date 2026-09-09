package com.claw.server.domain.capacity;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.CapacityPlanStatus;
import com.claw.server.common.enums.CapacitySubscriptionStatus;
import com.claw.server.common.enums.CapacityType;
import com.claw.server.common.enums.RebateStatus;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.manufacturer.Product;
import com.claw.server.domain.manufacturer.ProductRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.claw.server.domain.sharedpool.RentalOrder;
import com.claw.server.domain.sharedpool.RentalOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CapacityBookingService} 纯逻辑单元测试（不依赖 Spring / 真实 PG）。
 *
 * <p>覆盖：① 发布计划（默认回佣规则）；② 定购预付（复式记账 D 定购方 / C 厂家托管）；
 * ③ 租赁完成自动回佣（按 unit_count/total_units 二次拆分 + 尾差补首条）；
 * ④ V84「一订户一行、重复预定累加」：二次定购累加到同一行（不新增行）、记账只记本次增量；
 * ⑤ V84 唯一索引冲突（并发重复预定）被拦成 40973 / HTTP 409，不外泄数据库细节。
 *
 * <p>资金域隔离：所有余额/流水写入经 {@link AccountService}/{@link LedgerService}，
 * 本测试验证服务确实通过这两个出口，而非直接持有 ledger 仓储（与 ArchUnit 铁律一致）。
 */
class CapacityBookingServiceTest {

    @Mock
    private CapacityPlanRepository planRepository;
    @Mock
    private CapacitySubscriptionRepository subscriptionRepository;
    @Mock
    private CapacityRebateRuleRepository rebateRuleRepository;
    @Mock
    private CapacityRebateSettlementRepository rebateSettlementRepository;
    @Mock
    private RentalOrderRepository rentalOrderRepository;
    @Mock
    private AccountService accountService;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private SystemConfigRepository systemConfigRepository;
    @Mock
    private ProductRepository productRepository;

    private CapacityBookingService service;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new CapacityBookingService(planRepository, subscriptionRepository,
                rebateRuleRepository, rebateSettlementRepository, rentalOrderRepository,
                accountService, ledgerService, systemConfigRepository, productRepository);

        // 商品默认存在（V83 建计划前的存在性校验）；需要"商品不存在"的用例自己覆盖这个桩
        when(productRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.of(Product.builder().id(inv.getArgument(0)).build()));

        // 仓储 save 原样返回（注入自增 id）
        when(planRepository.save(any(CapacityPlan.class))).thenAnswer(inv -> {
            CapacityPlan p = inv.getArgument(0);
            if (p.getId() == null) p.setId(1L);
            return p;
        });
        when(subscriptionRepository.save(any(CapacitySubscription.class))).thenAnswer(inv -> {
            CapacitySubscription s = inv.getArgument(0);
            if (s.getId() == null) s.setId(100L);
            return s;
        });
        when(rebateSettlementRepository.save(any(CapacityRebateSettlement.class))).thenAnswer(inv -> inv.getArgument(0));

        // 账户：用户账户 id == userId；平台清算户固定 777
        when(accountService.getOrCreateUserAccount(anyLong()))
                .thenAnswer(inv -> Account.builder().id(inv.getArgument(0)).accountType(AccountType.MASTER).build());
        when(accountService.getOrCreatePlatformAccount(eq(AccountType.MASTER)))
                .thenReturn(Account.builder().id(777L).accountType(AccountType.MASTER).build());

        // V85 预付款验余额：默认余额充足；需要「余额不足」的用例自己覆盖这个桩
        when(ledgerService.getBalance(anyLong())).thenReturn(new BigDecimal("1000000.00"));

        // 记账：返回带 uuid 的 TxnResult（不校验金额，仅确认出口被调用）
        when(ledgerService.postEntries(any(), anyString(), anyList())).thenAnswer(inv -> {
            List<?> entries = inv.getArgument(2);
            return new LedgerViews.TxnResult(UUID.randomUUID(), inv.getArgument(0),
                    inv.getArgument(1), entries.size(), BigDecimal.ZERO, List.of());
        });
    }

    @Test
    @DisplayName("发布计划：状态 OPEN、类型 PARALLEL、默认回佣规则随计划 rate 落库")
    void createPlan_defaultsOpenAndRule() {
        // 计划挂在商品 1 上，发布方 900（登录态带出）；回佣率取配置默认 0.10
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("CAPACITY_REBATE_RATE_DEFAULT"))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey("CAPACITY_REBATE_RATE_DEFAULT").configValue("0.10").build()));

        CapacityPlan plan = service.createPlan(1L, 900L, 30, new BigDecimal("10.00"),
                Instant.now(), Instant.now().plusSeconds(86400), "风险提示：不保本；操作方法：填份数付款");

        assertEquals(CapacityPlanStatus.OPEN, plan.getStatus());
        assertEquals(30, plan.getTotalUnits());
        assertEquals(0, plan.getSubscribedUnits());
        assertEquals(1L, plan.getProductId());
        assertEquals(900L, plan.getOwnerUserId());
        // V81：容量类型固定 PARALLEL（并行共享，允许 top-up）
        assertEquals(CapacityType.PARALLEL, plan.getCapacityType());

        ArgumentCaptor<CapacityRebateRule> ruleCap = ArgumentCaptor.forClass(CapacityRebateRule.class);
        verify(rebateRuleRepository, times(1)).save(ruleCap.capture());
        CapacityRebateRule rule = ruleCap.getValue();
        assertEquals(plan.getId(), rule.getPlanId());
        assertEquals(0, rule.getRebateRate().compareTo(new BigDecimal("0.10")));
        assertEquals("ACTIVE", rule.getStatus());
    }

    @Test
    @DisplayName("发布计划：缺商品 / 缺份数 / 缺单价 / 缺说明 均参数校验失败")
    void createPlan_missingRequiredFields_throws() {
        String desc = "风险提示：不保本；操作方法：填份数付款";
        assertThrows(BizException.class, () -> service.createPlan(null, 900L, 30,
                new BigDecimal("10.00"), null, null, desc));
        assertThrows(BizException.class, () -> service.createPlan(1L, 900L, 0,
                new BigDecimal("10.00"), null, null, desc));
        assertThrows(BizException.class, () -> service.createPlan(1L, 900L, 30,
                null, null, null, desc));
        assertThrows(BizException.class, () -> service.createPlan(1L, 900L, 30,
                new BigDecimal("10.00"), null, null, "   "));
    }

    @Test
    @DisplayName("定购：预付金额=单价×份数，复式记账 D 定购方 / C 厂家托管")
    void subscribe_postsDoubleEntry() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).assetId(1L).ownerUserId(900L)
                .totalUnits(30).subscribedUnits(0).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.SERIAL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(subscriptionRepository.existsByPlanIdAndSubscriberUserIdAndDeletedFalse(1L, 200L)).thenReturn(false);

        CapacitySubscription sub = service.subscribe(1L, 200L, 3);

        // prepaid = 10.00 × 3 = 30.00
        assertEquals(0, sub.getPrepaidAmount().compareTo(new BigDecimal("30.00")));
        assertEquals(CapacitySubscriptionStatus.ACTIVE, sub.getStatus());
        assertNotNull(sub.getLedgerTxnId());
        // V81：付款即完成，落库时写付款时间戳
        assertNotNull(sub.getPaidAt());

        // 进度 +1
        assertEquals(3, plan.getSubscribedUnits());

        // 复式记账：1 借 1 贷，金额均为 30.00
        ArgumentCaptor<List<LedgerRequests.Entry>> cap = ArgumentCaptor.forClass(List.class);
        // 记账幂等键带累计份数后缀，保证同一订户每次付款都唯一（否则第二次付款被账本判重复过账）
        verify(ledgerService).postEntries(eq(BizType.CAPACITY_SUBSCRIPTION),
                eq("CAPSUB-" + sub.getId() + "-" + sub.getUnitCount()), cap.capture());
        List<LedgerRequests.Entry> entries = cap.getValue();
        assertEquals(2, entries.size());
        LedgerRequests.Entry debit = entries.get(0);
        LedgerRequests.Entry credit = entries.get(1);
        assertEquals(LedgerRequests.Direction.D, debit.direction());
        assertEquals(LedgerRequests.Direction.C, credit.direction());
        assertEquals(0, debit.amount().compareTo(new BigDecimal("30.00")));
        assertEquals(0, credit.amount().compareTo(new BigDecimal("30.00")));
        // D 定购方(200) / C 厂家(900)
        assertEquals(200L, debit.accountId());
        assertEquals(900L, credit.accountId());
    }

    @Test
    @DisplayName("定购超额：订阅份数超出上限抛 40972")
    void subscribe_exceedsCapacity_rejected() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).assetId(1L).ownerUserId(900L)
                .totalUnits(30).subscribedUnits(28).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.SERIAL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(subscriptionRepository.existsByPlanIdAndSubscriberUserIdAndDeletedFalse(1L, 200L)).thenReturn(false);

        assertThrows(com.claw.server.common.api.BizException.class, () -> service.subscribe(1L, 200L, 5));
    }

    @Test
    @DisplayName("租赁回佣：按 unit_count/total_units 二次拆分，复式记账 D 清算 / C 各定购方，尾差归首条")
    void applyRebateForRental_splitsByRatio() {
        // 订单：厂家 owner_share = 300.00
        RentalOrder order = new RentalOrder();
        order.setId(1L);
        order.setAssetId(1L);
        order.setOrderNo("RO-1");
        order.setPoolEntryId(10L);
        order.setOwnerShare(new BigDecimal("300.00"));
        when(rentalOrderRepository.findById(1L)).thenReturn(Optional.of(order));

        // 开放计划，totalUnits=30
        CapacityPlan plan = CapacityPlan.builder().id(1L).assetId(1L).ownerUserId(900L)
                .totalUnits(30).subscribedUnits(30).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.SERIAL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findFirstByAssetIdAndDeletedFalseOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(plan));

        // 两个定购单位：A=10 份(用户200) / B=20 份(用户300)
        CapacitySubscription subA = CapacitySubscription.builder().id(100L).planId(1L)
                .subscriberUserId(200L).unitCount(10).status(CapacitySubscriptionStatus.ACTIVE).build();
        CapacitySubscription subB = CapacitySubscription.builder().id(101L).planId(1L)
                .subscriberUserId(300L).unitCount(20).status(CapacitySubscriptionStatus.ACTIVE).build();
        when(subscriptionRepository.findByPlanIdAndStatusAndDeletedFalse(1L, CapacitySubscriptionStatus.ACTIVE))
                .thenReturn(List.of(subA, subB));

        CapacityRebateRule rule = CapacityRebateRule.builder().id(5L).planId(1L)
                .rebateRate(new BigDecimal("0.10")).minPayout(new BigDecimal("0.01")).status("ACTIVE").build();
        when(rebateRuleRepository.findFirstByPlanIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(1L, "ACTIVE"))
                .thenReturn(Optional.of(rule));

        int rows = service.applyRebateForRental(1L);

        // 回佣总额 = 300 × 0.10 = 30.00；A 得 10.00，B 得 20.00
        assertEquals(2, rows);

        ArgumentCaptor<List<LedgerRequests.Entry>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postEntries(eq(BizType.CAPACITY_REBATE), eq("REBATE-RO-1"), cap.capture());
        List<LedgerRequests.Entry> entries = cap.getValue();
        // D 清算(777)=30.00, C 200=10.00, C 300=20.00
        assertEquals(3, entries.size());
        LedgerRequests.Entry debit = entries.get(0);
        assertEquals(LedgerRequests.Direction.D, debit.direction());
        assertEquals(777L, debit.accountId());
        assertEquals(0, debit.amount().compareTo(new BigDecimal("30.00")));

        BigDecimal sumCredit = BigDecimal.ZERO;
        for (LedgerRequests.Entry e : entries.subList(1, entries.size())) {
            assertEquals(LedgerRequests.Direction.C, e.direction());
            sumCredit = sumCredit.add(e.amount());
        }
        assertEquals(0, sumCredit.compareTo(new BigDecimal("30.00")));
        // 各定购方应得：200→10.00, 300→20.00
        assertEquals(0, entries.get(1).amount().compareTo(new BigDecimal("10.00")));
        assertEquals(0, entries.get(2).amount().compareTo(new BigDecimal("20.00")));

        // 回佣明细：2 行，状态 SETTLED，金额/比例正确
        ArgumentCaptor<CapacityRebateSettlement> settleCap = ArgumentCaptor.forClass(CapacityRebateSettlement.class);
        verify(rebateSettlementRepository, times(2)).save(settleCap.capture());
        List<CapacityRebateSettlement> saved = settleCap.getAllValues();
        CapacityRebateSettlement a = saved.stream().filter(s -> s.getSubscriberUserId() == 200L).findFirst().orElseThrow();
        CapacityRebateSettlement b = saved.stream().filter(s -> s.getSubscriberUserId() == 300L).findFirst().orElseThrow();
        assertEquals(0, a.getAmount().compareTo(new BigDecimal("10.0000")));
        assertEquals(0, b.getAmount().compareTo(new BigDecimal("20.0000")));
        assertEquals(0, a.getRatio().compareTo(new BigDecimal("0.333333")));
        assertEquals(0, b.getRatio().compareTo(new BigDecimal("0.666667")));
        assertEquals(RebateStatus.SETTLED, a.getStatus());
        assertEquals(0, a.getRebateTotal().compareTo(new BigDecimal("30.00")));
    }

    @Test
    @DisplayName("租赁回佣：owner_share 为零 / 无开放计划 / 无定购 时安全返回 0")
    void applyRebateForRental_noopPaths_returnZero() {
        // ownerShare 为 0 → 0
        RentalOrder zero = new RentalOrder();
        zero.setId(2L);
        zero.setAssetId(1L);
        zero.setOrderNo("RO-ZERO");
        zero.setOwnerShare(BigDecimal.ZERO);
        when(rentalOrderRepository.findById(2L)).thenReturn(Optional.of(zero));
        assertEquals(0, service.applyRebateForRental(2L));

        // 找不到开放计划 → 0
        RentalOrder ord = new RentalOrder();
        ord.setId(3L);
        ord.setAssetId(2L);
        ord.setOrderNo("RO-3");
        ord.setOwnerShare(new BigDecimal("100.00"));
        when(rentalOrderRepository.findById(3L)).thenReturn(Optional.of(ord));
        when(planRepository.findFirstByAssetIdAndDeletedFalseOrderByCreatedAtDesc(2L)).thenReturn(Optional.empty());
        assertEquals(0, service.applyRebateForRental(3L));
    }

    @Test
    @DisplayName("SERIAL：同一订户二次定购被 40971 拒绝")
    void serial_subscribe_rejectsDuplicate() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).assetId(1L).ownerUserId(900L)
                .totalUnits(30).subscribedUnits(0).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.SERIAL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(subscriptionRepository.existsByPlanIdAndSubscriberUserIdAndDeletedFalse(1L, 200L)).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> service.subscribe(1L, 200L, 3));
        assertEquals(40971, ex.getCode());
    }

    @Test
    @DisplayName("PARALLEL：首次定购新增一行（无既有 ACTIVE 行时走 insert）")
    void parallel_subscribe_firstTimeCreatesRow() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).assetId(1L).ownerUserId(900L)
                .totalUnits(30).subscribedUnits(0).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.PARALLEL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        // 尚无累计份数；既有 ACTIVE 行查询未打桩 → Optional.empty()（Mockito 对 Optional 返回类型的默认值）
        when(subscriptionRepository.sumUnitCountByPlanIdAndSubscriberUserId(1L, 200L)).thenReturn(0L);

        CapacitySubscription sub = service.subscribe(1L, 200L, 5);

        assertEquals(CapacitySubscriptionStatus.ACTIVE, sub.getStatus());
        assertEquals(5, sub.getUnitCount());
        assertEquals(0, sub.getPrepaidAmount().compareTo(new BigDecimal("50.00")));
    }

    @Test
    @DisplayName("PARALLEL：同一订户二次定购 → 累加到同一行（不新增行），金额/份数为两次之和，记账只记本次增量")
    void parallel_subscribe_secondTimeAccumulatesOnSameRow() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).assetId(1L).ownerUserId(900L)
                .totalUnits(30).subscribedUnits(5).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.PARALLEL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        // 第一次已订 5 份
        when(subscriptionRepository.sumUnitCountByPlanIdAndSubscriberUserId(1L, 200L)).thenReturn(5L);
        CapacitySubscription existing = CapacitySubscription.builder().id(100L).planId(1L)
                .subscriberUserId(200L).unitCount(5).prepaidAmount(new BigDecimal("50.00"))
                .status(CapacitySubscriptionStatus.ACTIVE).build();
        when(subscriptionRepository.findByPlanIdAndSubscriberUserIdAndStatusAndDeletedFalse(
                1L, 200L, CapacitySubscriptionStatus.ACTIVE)).thenReturn(Optional.of(existing));

        CapacitySubscription sub = service.subscribe(1L, 200L, 3);

        // ① 累加到既有行：还是 id=100 那一行
        assertEquals(100L, sub.getId());
        // ② 份数 = 5 + 3 = 8；金额 = 50.00 + 30.00 = 80.00
        assertEquals(8, sub.getUnitCount());
        assertEquals(0, sub.getPrepaidAmount().compareTo(new BigDecimal("80.00")));
        assertEquals(CapacitySubscriptionStatus.ACTIVE, sub.getStatus());
        assertNotNull(sub.getPaidAt());
        // ③ 表里仍只有 1 行：所有 save 过的实体去重后只有一个 id
        ArgumentCaptor<CapacitySubscription> subCap = ArgumentCaptor.forClass(CapacitySubscription.class);
        verify(subscriptionRepository, atLeastOnce()).save(subCap.capture());
        assertEquals(1, subCap.getAllValues().stream()
                .map(CapacitySubscription::getId)
                .distinct()
                .count());

        // ④ 记账金额 = 本次增量 10.00 × 3 = 30.00，不是累加后的 80.00（历史部分上次已划转）
        ArgumentCaptor<List<LedgerRequests.Entry>> cap = ArgumentCaptor.forClass(List.class);
        // 追加后累计 8 份，幂等键为 CAPSUB-100-8（唯一，不会被账本判为重复过账）
        verify(ledgerService).postEntries(eq(BizType.CAPACITY_SUBSCRIPTION), eq("CAPSUB-100-8"), cap.capture());
        List<LedgerRequests.Entry> entries = cap.getValue();
        assertEquals(2, entries.size());
        assertEquals(LedgerRequests.Direction.D, entries.get(0).direction());
        assertEquals(LedgerRequests.Direction.C, entries.get(1).direction());
        assertEquals(0, entries.get(0).amount().compareTo(new BigDecimal("30.00")));
        assertEquals(0, entries.get(1).amount().compareTo(new BigDecimal("30.00")));

        // ⑤ 计划进度只加本次增量：5 + 3 = 8
        assertEquals(8, plan.getSubscribedUnits());
    }

    @Test
    @DisplayName("PARALLEL：累加后超出总容量仍抛 40972")
    void parallel_subscribe_accumulateExceedsCapacity_rejected() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).assetId(1L).ownerUserId(900L)
                .totalUnits(30).subscribedUnits(5).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.PARALLEL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(subscriptionRepository.sumUnitCountByPlanIdAndSubscriberUserId(1L, 200L)).thenReturn(5L);

        // 已订 5 份 + 本次 30 份 = 35 > 30
        BizException ex = assertThrows(BizException.class, () -> service.subscribe(1L, 200L, 30));
        assertEquals(40972, ex.getCode());
    }

    @Test
    @DisplayName("V85：预付款余额不足直接拒绝（40974），既不记账也不落定购行——不允许透支")
    void subscribe_insufficientBalanceRejected() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).productId(1L).ownerUserId(900L)
                .totalUnits(100).subscribedUnits(0).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.PARALLEL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(subscriptionRepository.sumUnitCountByPlanIdAndSubscriberUserId(1L, 500L)).thenReturn(null);
        when(subscriptionRepository.findByPlanIdAndSubscriberUserIdAndStatusAndDeletedFalse(
                1L, 500L, CapacitySubscriptionStatus.ACTIVE)).thenReturn(Optional.empty());
        // 订户账户只有 5.00，本次应付 3 × 10.00 = 30.00 → 必须被拒
        when(ledgerService.getBalance(500L)).thenReturn(new BigDecimal("5.00"));

        BizException ex = assertThrows(BizException.class, () -> service.subscribe(1L, 500L, 3));
        assertEquals(40974, ex.getCode());

        // 关键：一分钱都没划走，也没有留下任何定购行
        verify(ledgerService, times(0)).postEntries(any(), anyString(), anyList());
        verify(subscriptionRepository, times(0)).save(any(CapacitySubscription.class));
    }

    @Test
    @DisplayName("V85：余额刚好等于应付金额时允许预定（边界不应误杀）")
    void subscribe_balanceExactlyEnoughAccepted() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).productId(1L).ownerUserId(900L)
                .totalUnits(100).subscribedUnits(0).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.PARALLEL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(subscriptionRepository.sumUnitCountByPlanIdAndSubscriberUserId(1L, 500L)).thenReturn(null);
        when(subscriptionRepository.findByPlanIdAndSubscriberUserIdAndStatusAndDeletedFalse(
                1L, 500L, CapacitySubscriptionStatus.ACTIVE)).thenReturn(Optional.empty());
        // 余额 30.00 == 应付 30.00
        when(ledgerService.getBalance(500L)).thenReturn(new BigDecimal("30.00"));

        CapacitySubscription sub = service.subscribe(1L, 500L, 3);
        assertEquals(3, sub.getUnitCount());
        verify(ledgerService).postEntries(eq(BizType.CAPACITY_SUBSCRIPTION), anyString(), anyList());
    }

    @Test
    @DisplayName("唯一索引冲突：DataIntegrityViolationException 被转成 40973（HTTP 409），绝不 500、不吐数据库细节")
    void subscribe_uniqueConstraintViolation_translatedTo409() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).assetId(1L).ownerUserId(900L)
                .totalUnits(30).subscribedUnits(0).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.PARALLEL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(subscriptionRepository.sumUnitCountByPlanIdAndSubscriberUserId(1L, 200L)).thenReturn(0L);
        when(subscriptionRepository.findByPlanIdAndSubscriberUserIdAndStatusAndDeletedFalse(
                1L, 200L, CapacitySubscriptionStatus.ACTIVE)).thenReturn(Optional.empty());
        // 模拟并发下两个请求同时判定「无既有行」，其中一个 insert 撞 uq_cap_sub_active
        when(subscriptionRepository.save(any(CapacitySubscription.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_cap_sub_active\""));

        BizException ex = assertThrows(BizException.class, () -> service.subscribe(1L, 200L, 3));

        assertEquals(40973, ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.httpStatus());
        // 数据库约束名/字段值绝不能出现在返回给前端的 message 里
        assertFalse(ex.getMessage().contains("uq_cap_sub_active"));
        assertFalse(ex.getMessage().contains("duplicate key"));
        assertFalse(ex.getMessage().contains("plan_id"));
    }

    @Test
    @DisplayName("PARALLEL：同一订户可 top-up 多行，回佣按各行 unitCount/totalUnits 拆分")
    void parallel_topUp_splitsRebateAcrossRows() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).assetId(1L).ownerUserId(900L)
                .totalUnits(15).subscribedUnits(15).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.PARALLEL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();

        RentalOrder order = new RentalOrder();
        order.setId(5L);
        order.setAssetId(1L);
        order.setOrderNo("RO-P");
        order.setPoolEntryId(10L);
        order.setOwnerShare(new BigDecimal("300.00"));
        when(rentalOrderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(planRepository.findFirstByAssetIdAndDeletedFalseOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(plan));

        // 同一订户两行 top-up：10 + 5 = 15 份
        CapacitySubscription sub1 = CapacitySubscription.builder().id(100L).planId(1L)
                .subscriberUserId(400L).unitCount(10).status(CapacitySubscriptionStatus.ACTIVE).build();
        CapacitySubscription sub2 = CapacitySubscription.builder().id(101L).planId(1L)
                .subscriberUserId(400L).unitCount(5).status(CapacitySubscriptionStatus.ACTIVE).build();
        when(subscriptionRepository.findByPlanIdAndStatusAndDeletedFalse(1L, CapacitySubscriptionStatus.ACTIVE))
                .thenReturn(List.of(sub1, sub2));

        CapacityRebateRule rule = CapacityRebateRule.builder().id(5L).planId(1L)
                .rebateRate(new BigDecimal("0.10")).minPayout(new BigDecimal("0.01")).status("ACTIVE").build();
        when(rebateRuleRepository.findFirstByPlanIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(1L, "ACTIVE"))
                .thenReturn(Optional.of(rule));

        int rows = service.applyRebateForRental(5L);
        assertEquals(2, rows);

        ArgumentCaptor<CapacityRebateSettlement> cap = ArgumentCaptor.forClass(CapacityRebateSettlement.class);
        verify(rebateSettlementRepository, times(2)).save(cap.capture());
        List<CapacityRebateSettlement> saved = cap.getAllValues();
        CapacityRebateSettlement a = saved.stream().filter(s -> s.getUnitCount() == 10).findFirst().orElseThrow();
        CapacityRebateSettlement b = saved.stream().filter(s -> s.getUnitCount() == 5).findFirst().orElseThrow();
        // rebateTotal = 300 × 0.10 = 30.00；按比例 10/15、5/15 拆分：A=20.00(0.666667)，B=10.00(0.333333)
        assertEquals(0, a.getAmount().compareTo(new BigDecimal("20.0000")));
        assertEquals(0, b.getAmount().compareTo(new BigDecimal("10.0000")));
        assertEquals(0, a.getRatio().compareTo(new BigDecimal("0.666667")));
        assertEquals(0, b.getRatio().compareTo(new BigDecimal("0.333333")));
    }

    @Test
    @DisplayName("发布计划：商品不存在 / 已软删 时抛 40401 product.not.found，绝不建出悬空计划")
    void createPlan_productMissingOrDeleted_throws40401() {
        String desc = "风险提示：不保本；操作方法：填份数付款";
        // 商品不存在
        when(productRepository.findById(404L)).thenReturn(Optional.empty());
        BizException missing = assertThrows(BizException.class, () -> service.createPlan(404L, 900L, 30,
                new BigDecimal("10.00"), null, null, desc));
        assertEquals(40401, missing.getCode());

        // 商品存在但已软删
        when(productRepository.findById(405L))
                .thenReturn(Optional.of(Product.builder().id(405L).deleted(true).build()));
        BizException deleted = assertThrows(BizException.class, () -> service.createPlan(405L, 900L, 30,
                new BigDecimal("10.00"), null, null, desc));
        assertEquals(40401, deleted.getCode());

        // 两种情形都不允许落库任何计划
        verify(planRepository, times(0)).save(any(CapacityPlan.class));
    }

    @Test
    @DisplayName("createPlan：回佣率取配置默认，并被上限 CAPACITY_REBATE_RATE_MAX 夹紧")
    void createPlan_rebateRateClampedToMax() {
        // 配置默认被误配成 0.50（> 上限 0.30），落库前夹紧到 0.30，不因脏配置越界
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("CAPACITY_REBATE_RATE_DEFAULT"))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey("CAPACITY_REBATE_RATE_DEFAULT").configValue("0.50").build()));
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("CAPACITY_REBATE_RATE_MAX"))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey("CAPACITY_REBATE_RATE_MAX").configValue("0.30").build()));

        CapacityPlan plan = service.createPlan(1L, 900L, 30, new BigDecimal("10.00"),
                null, null, "风险提示：不保本；操作方法：填份数付款");
        assertEquals(0, plan.getRebateRate().compareTo(new BigDecimal("0.30")));
    }

    @Test
    @DisplayName("createPlan：回佣率缺省时取配置默认 0.10")
    void createPlan_usesConfiguredDefault() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("CAPACITY_REBATE_RATE_DEFAULT"))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey("CAPACITY_REBATE_RATE_DEFAULT").configValue("0.10").build()));

        CapacityPlan plan = service.createPlan(1L, 900L, 30, new BigDecimal("10.00"),
                null, null, "风险提示：不保本；操作方法：填份数付款");
        assertEquals(0, plan.getRebateRate().compareTo(new BigDecimal("0.10")));
    }
}
