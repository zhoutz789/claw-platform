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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CapacityBookingService} 纯逻辑单元测试（不依赖 Spring / 真实 PG）。
 *
 * <p>覆盖：① 发布计划（默认回佣规则）；② 定购预付（复式记账 D 定购方 / C 厂家托管）；
 * ③ 租赁完成自动回佣（按 unit_count/total_units 二次拆分 + 尾差补首条）。
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

    private CapacityBookingService service;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new CapacityBookingService(planRepository, subscriptionRepository,
                rebateRuleRepository, rebateSettlementRepository, rentalOrderRepository,
                accountService, ledgerService, systemConfigRepository);

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

        // 记账：返回带 uuid 的 TxnResult（不校验金额，仅确认出口被调用）
        when(ledgerService.postEntries(any(), anyString(), anyList())).thenAnswer(inv -> {
            List<?> entries = inv.getArgument(2);
            return new LedgerViews.TxnResult(UUID.randomUUID(), inv.getArgument(0),
                    inv.getArgument(1), entries.size(), BigDecimal.ZERO, List.of());
        });
    }

    @Test
    @DisplayName("发布计划：状态 OPEN，默认回佣规则随计划 rate 落库")
    void createPlan_defaultsOpenAndRule() {
        CapacityPlan plan = service.createPlan(1L, 10L, 900L, 30, new BigDecimal("10.00"),
                CapacityType.SERIAL, new BigDecimal("0.10"), Instant.now(), Instant.now().plusSeconds(86400));

        assertEquals(CapacityPlanStatus.OPEN, plan.getStatus());
        assertEquals(30, plan.getTotalUnits());
        assertEquals(0, plan.getSubscribedUnits());
        assertEquals(CapacityType.SERIAL, plan.getCapacityType());

        ArgumentCaptor<CapacityRebateRule> ruleCap = ArgumentCaptor.forClass(CapacityRebateRule.class);
        verify(rebateRuleRepository, times(1)).save(ruleCap.capture());
        CapacityRebateRule rule = ruleCap.getValue();
        assertEquals(plan.getId(), rule.getPlanId());
        assertEquals(0, rule.getRebateRate().compareTo(new BigDecimal("0.10")));
        assertEquals("ACTIVE", rule.getStatus());
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

        // 进度 +1
        assertEquals(3, plan.getSubscribedUnits());

        // 复式记账：1 借 1 贷，金额均为 30.00
        ArgumentCaptor<List<LedgerRequests.Entry>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postEntries(eq(BizType.CAPACITY_SUBSCRIPTION), eq("CAPSUB-" + sub.getId()), cap.capture());
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
    @DisplayName("PARALLEL：同一订户二次定购（top-up）不被 40971 拒绝，累计不超总容量")
    void parallel_subscribe_allowsTopUp() {
        CapacityPlan plan = CapacityPlan.builder().id(1L).assetId(1L).ownerUserId(900L)
                .totalUnits(30).subscribedUnits(0).unitPrice(new BigDecimal("10.00"))
                .capacityType(CapacityType.PARALLEL).rebateRate(new BigDecimal("0.10"))
                .status(CapacityPlanStatus.OPEN).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        // 第一次已订 5 份（PARALLEL 不查 exists，只查累计份数）
        when(subscriptionRepository.sumUnitCountByPlanIdAndSubscriberUserId(1L, 200L)).thenReturn(5L);

        CapacitySubscription sub = service.subscribe(1L, 200L, 5); // 5 + 5 = 10 <= 30
        assertEquals(CapacitySubscriptionStatus.ACTIVE, sub.getStatus());
        assertEquals(5, sub.getUnitCount());
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
    @DisplayName("createPlan：rebateRate 超上限抛 40973")
    void createPlan_rebateRateExceedsMax_throws() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("CAPACITY_REBATE_RATE_MAX"))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey("CAPACITY_REBATE_RATE_MAX").configValue("0.30").build()));

        BizException ex = assertThrows(BizException.class, () -> service.createPlan(
                1L, 10L, 900L, 30, new BigDecimal("10.00"), CapacityType.SERIAL,
                new BigDecimal("0.50"), Instant.now(), Instant.now().plusSeconds(86400)));
        assertEquals(40973, ex.getCode());
    }

    @Test
    @DisplayName("createPlan：rebateRate 为 null 时取配置默认 0.10")
    void createPlan_nullRebateRate_usesDefault() {
        when(systemConfigRepository.findByConfigKeyAndDeletedFalse("CAPACITY_REBATE_RATE_DEFAULT"))
                .thenReturn(Optional.of(SystemConfig.builder()
                        .configKey("CAPACITY_REBATE_RATE_DEFAULT").configValue("0.10").build()));

        CapacityPlan plan = service.createPlan(1L, 10L, 900L, 30, new BigDecimal("10.00"),
                CapacityType.SERIAL, null, Instant.now(), Instant.now().plusSeconds(86400));
        assertEquals(0, plan.getRebateRate().compareTo(new BigDecimal("0.10")));
    }
}
