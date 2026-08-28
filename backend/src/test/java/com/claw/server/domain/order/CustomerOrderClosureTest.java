package com.claw.server.domain.order;

import com.claw.server.common.dto.DepositViews;
import com.claw.server.common.dto.OrderDtos.*;
import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.CertificateType;
import com.claw.server.common.enums.OrderStatus;
import com.claw.server.common.enums.OwnershipStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.enums.SettlementStatus;
import com.claw.server.common.enums.UsageMode;
import com.claw.server.domain.deposit.DepositRuleService;
import com.claw.server.domain.deposit.DepositService;
import com.claw.server.domain.order.event.OrderCompletedEvent;
import com.claw.server.domain.order.event.OrderPaidEvent;
import com.claw.server.domain.order.event.OrderSettlementIntegration;
import com.claw.server.domain.order.event.OrderSharedPoolIntegration;
import com.claw.server.domain.sharedpool.RevenueSettlement;
import com.claw.server.domain.sharedpool.SharedPoolEntry;
import com.claw.server.domain.sharedpool.SharedPoolService;
import com.claw.server.domain.sharedpool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 客户订单闭环 happy-path 单测（Mockito，无需真实数据库）：
 * create → pay（建产权 + 冻押金 + 发 OrderPaidEvent）→ choose-mode(SHARED)
 * → 监听器入池（断言 poolEntryId/splitRuleId 非空）→ ship → complete（断言发 OrderCompletedEvent）
 * → 分账集成（断言 createSettlement 建 PENDING 结算）。
 *
 * <p>跨域仅经 SharedPoolService 服务与领域事件，证明链路接通；
 * 不依赖 ledger 仓储直查（满足 ArchUnit 边界）。
 */
@ExtendWith(MockitoExtension.class)
class CustomerOrderClosureTest {

    @Mock private CustomerOrderRepository orderRepository;
    @Mock private CustomerOrderItemRepository orderItemRepository;
    @Mock private DepositService depositService;
    @Mock private DepositRuleService depositRuleService;
    @Mock private SharedPoolService sharedPoolService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CertificateRepository certificateRepository;
    @Mock private SharedPoolEntryRepository poolEntryRepository;
    @Mock private RentalOrderRepository rentalOrderRepository;
    @Mock private RentalUsageSessionRepository usageSessionRepository;
    @Mock private RevenueSplitRuleRepository splitRuleRepository;
    @Mock private AssetOwnershipRepository ownershipRepository;
    @Mock private RevenueSettlementRepository settlementRepository;

    @InjectMocks private CustomerOrderService orderService;

    private CustomerOrder newOrder(OrderStatus status) {
        CustomerOrder o = new CustomerOrder();
        o.setId(1L);
        o.setOrderNo("ORD-20260827-TEST0001");
        o.setBuyerUserId(5L);
        o.setAssetId(10L);
        o.setAssetType("EV");
        o.setUsageMode(UsageMode.SELF);
        o.setStatus(status);
        o.setTotalAmount(new BigDecimal("100000.0000"));
        o.setTenantId(1L);
        o.setDeleted(false);
        return o;
    }

    @Test
    void happy_path_create_pay_chooseMode_ship_complete_and_settlement() {
        CustomerOrder order = newOrder(OrderStatus.CREATED);

        // ---- create ----
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.save(any(CustomerOrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
        CustomerOrderView created = orderService.create(
                new CreateCustomerOrderReq(5L, 10L, 20L, 30L, "EV", 1, new BigDecimal("100000.0000"), null));
        assertEquals("CREATED", created.status());

        // ---- pay ----
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(depositRuleService.computeDepositAmount(any(), any(), anyInt()))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("0.30")));
        when(depositService.hold(any())).thenReturn(new DepositViews.DepositView(
                1L, "DEP-TEST", 5L, 10L, new BigDecimal("30000.0000"), "HELD", "PO1", null, Instant.now(), Instant.now()));
        when(sharedPoolService.establishOwnership(any(), any(), any(), any())).thenReturn(null);

        ArgumentCaptor<Object> payEvents = ArgumentCaptor.forClass(Object.class);
        CustomerOrderView paid = orderService.pay(1L, new PayReq("PO1", new BigDecimal("100000.0000")));

        assertEquals("PAID", paid.status());
        assertEquals("DEP-TEST", paid.depositNo());
        assertEquals(0, new BigDecimal("30000.0000").compareTo(paid.depositAmount()));

        // 断言：建产权（FULL）被调用
        verify(sharedPoolService).establishOwnership(eq(10L), eq(5L),
                argThat(b -> b != null && b.compareTo(new BigDecimal("100000.0000")) == 0), eq("PO1"));
        // 断言：冻押金被调用（只动台账）
        verify(depositService).hold(argThat(h -> h != null
                && Long.valueOf(10L).equals(h.assetId())
                && Long.valueOf(5L).equals(h.userId())
                && new BigDecimal("30000.0000").compareTo(h.amount()) == 0));
        // 断言：发 OrderPaidEvent
        verify(eventPublisher, atLeast(1)).publishEvent(payEvents.capture());
        assertTrue(payEvents.getAllValues().stream().anyMatch(e -> e instanceof OrderPaidEvent));
        OrderPaidEvent paidEvent = (OrderPaidEvent) payEvents.getAllValues().stream()
                .filter(e -> e instanceof OrderPaidEvent).findFirst().get();
        assertEquals(1L, paidEvent.getOrderId());
        assertEquals(10L, paidEvent.getAssetId());

        // ---- choose-mode SHARED ----
        order.setStatus(OrderStatus.PAID);
        ArgumentCaptor<Object> modeEvents = ArgumentCaptor.forClass(Object.class);
        CustomerOrderView chosen = orderService.chooseMode(1L, new ChooseModeReq("SHARED", 99L));
        assertEquals("SHARED", chosen.usageMode());
        assertEquals(99L, chosen.stationId());
        // 切 SHARED 重发 OrderPaidEvent，供监听器重新评估入池
        verify(eventPublisher, atLeast(2)).publishEvent(modeEvents.capture());
        assertTrue(modeEvents.getAllValues().stream().anyMatch(e -> e instanceof OrderPaidEvent));

        // ---- 监听器入池（SHARED）----
        OrderSharedPoolIntegration integration = new OrderSharedPoolIntegration(sharedPoolService, orderRepository);
        order.setUsageMode(UsageMode.SHARED);
        order.setStationId(99L);
        order.setPoolEntryId(null);
        when(sharedPoolService.poolAsset(eq(10L), eq(5L), eq(99L), any(), any(), any(), any()))
                .thenReturn(SharedPoolEntry.builder().id(100L).assetId(10L).build());
        when(sharedPoolService.createSplitRule(eq(10L), eq(100L), any(), any())).thenReturn(200L);

        integration.onOrderPaid(new OrderPaidEvent(1L, 10L, 5L, 99L, "SHARED"));
        assertNotNull(order.getPoolEntryId());
        assertNotNull(order.getSplitRuleId());
        assertEquals(100L, order.getPoolEntryId());
        assertEquals(200L, order.getSplitRuleId());

        // ---- ship ----
        order.setStatus(OrderStatus.PAID);
        order.setPoolEntryId(100L);
        order.setSplitRuleId(200L);
        CustomerOrderView shipped = orderService.ship(1L);
        assertEquals("SHIPPED", shipped.status());
        assertNotNull(shipped.shippedAt());

        // ---- complete ----
        order.setStatus(OrderStatus.SHIPPED);
        ArgumentCaptor<Object> doneEvents = ArgumentCaptor.forClass(Object.class);
        CustomerOrderView completed = orderService.complete(1L);
        assertEquals("COMPLETED", completed.status());
        verify(eventPublisher, atLeast(1)).publishEvent(doneEvents.capture());
        assertTrue(doneEvents.getAllValues().stream().anyMatch(e -> e instanceof OrderCompletedEvent));
        OrderCompletedEvent doneEvent = (OrderCompletedEvent) doneEvents.getAllValues().stream()
                .filter(e -> e instanceof OrderCompletedEvent).findFirst().get();
        assertEquals(100L, doneEvent.getPoolEntryId());

        // ---- 分账集成：建 RevenueSettlement(PENDING) ----
        OrderSettlementIntegration settle = new OrderSettlementIntegration(sharedPoolService);
        when(sharedPoolService.createSettlement(eq(100L), any(), any()))
                .thenReturn(RevenueSettlement.builder()
                        .id(500L)
                        .settlementNo("SET-20260827-TEST0001")
                        .settlementDate(LocalDate.now())
                        .poolEntryId(100L)
                        .totalRevenue(BigDecimal.ZERO)
                        .ownerShare(BigDecimal.ZERO)
                        .stationShare(BigDecimal.ZERO)
                        .platformShare(BigDecimal.ZERO)
                        .insuranceShare(BigDecimal.ZERO)
                        .status(SettlementStatus.PENDING)
                        .periodStart(Instant.now())
                        .periodEnd(Instant.now())
                        .tenantId(1L)
                        .deleted(false)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build());
        settle.onOrderCompleted(new OrderCompletedEvent(1L, 100L));
        verify(sharedPoolService).createSettlement(eq(100L), any(), any());
    }

    // ===== 状态机守卫：非法跳变应拒绝 =====

    @Test
    void ship_before_paid_should_reject() {
        CustomerOrder order = newOrder(OrderStatus.CREATED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        assertThrows(BizException.class, () -> orderService.ship(1L));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void complete_before_shipped_should_reject() {
        CustomerOrder order = newOrder(OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        assertThrows(BizException.class, () -> orderService.complete(1L));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void pay_when_not_created_should_reject() {
        CustomerOrder order = newOrder(OrderStatus.PAID); // 已支付，重复支付须拒绝
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        assertThrows(BizException.class,
                () -> orderService.pay(1L, new PayReq("PO1", new BigDecimal("100000.0000"))));
        verify(sharedPoolService, never()).establishOwnership(any(), any(), any(), any());
    }

    // ===== choose-mode SELF 不入池 =====

    @Test
    void choose_mode_self_does_not_publish_pool_event() {
        CustomerOrder order = newOrder(OrderStatus.PAID);
        order.setUsageMode(UsageMode.SELF);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        CustomerOrderView chosen = orderService.chooseMode(1L, new ChooseModeReq("SELF", null));
        assertEquals("SELF", chosen.usageMode());
        verify(eventPublisher, never()).publishEvent(any(OrderPaidEvent.class));
    }

    @Test
    void listener_skips_pool_when_self_mode() {
        CustomerOrder order = newOrder(OrderStatus.PAID);
        order.setUsageMode(UsageMode.SELF);
        order.setPoolEntryId(null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        OrderSharedPoolIntegration integration = new OrderSharedPoolIntegration(sharedPoolService, orderRepository);
        integration.onOrderPaid(new OrderPaidEvent(1L, 10L, 5L, null, "SELF"));
        verify(sharedPoolService, never()).poolAsset(any(), any(), any(), any(), any(), any(), any());
        assertNull(order.getPoolEntryId());
    }

    // ===== 合格证：幂等 + 状态守卫（原 happy-path 测试未覆盖）=====

    @Test
    void certificate_is_idempotent_and_sets_certificated() {
        CustomerOrder order = newOrder(OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(certificateRepository.findByOrderIdAndCertTypeAndDeletedFalse(eq(1L), eq(CertificateType.QUALIFICATION)))
                .thenReturn(Optional.empty());
        when(certificateRepository.save(any(Certificate.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderCertificateService certService = new OrderCertificateService(certificateRepository, orderRepository);
        CertificateView first = certService.issueOrGet(1L);
        assertNotNull(first.certNo());

        // 第二次调用：已出具，应直接返回同一 cert_no，不再建证书
        Certificate saved = Certificate.builder().certType(CertificateType.QUALIFICATION)
                .certNo(first.certNo()).orderId(1L).assetId(10L).deleted(false).build();
        when(certificateRepository.findByOrderIdAndCertTypeAndDeletedFalse(eq(1L), eq(CertificateType.QUALIFICATION)))
                .thenReturn(Optional.of(saved));
        CertificateView second = certService.issueOrGet(1L);
        assertEquals(first.certNo(), second.certNo());
        verify(certificateRepository, times(1)).save(any(Certificate.class));
    }

    @Test
    void certificate_before_paid_should_reject() {
        CustomerOrder order = newOrder(OrderStatus.CREATED); // 未支付
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        OrderCertificateService certService = new OrderCertificateService(certificateRepository, orderRepository);
        assertThrows(BizException.class, () -> certService.issueOrGet(1L));
        verify(certificateRepository, never()).save(any());
    }

    // ===== SharedPoolService 真实分成/产权/分账口径（原 happy-path 被 mock 绕过）=====

    @Test
    void shared_pool_split_rule_and_ownership_are_correct() {
        when(splitRuleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ownershipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SharedPoolService realPool = new SharedPoolService(poolEntryRepository, rentalOrderRepository,
                usageSessionRepository, splitRuleRepository, ownershipRepository, settlementRepository);

        AssetOwnership ownership = realPool.establishOwnership(10L, 5L, new BigDecimal("100000.0000"), "PO1");
        assertEquals(OwnershipType.FULL, ownership.getOwnershipType());
        assertEquals(OwnershipStatus.ACTIVE, ownership.getStatus());
        assertNotNull(ownership.getPurchaseDate());

        realPool.createSplitRule(10L, 100L, new BigDecimal("0.70"), new BigDecimal("0.15"));
        ArgumentCaptor<RevenueSplitRule> ruleCaptor = ArgumentCaptor.forClass(RevenueSplitRule.class);
        verify(splitRuleRepository).save(ruleCaptor.capture());
        RevenueSplitRule rule = ruleCaptor.getValue();
        assertEquals(0, new BigDecimal("0.70").compareTo(rule.getOwnerRate()));
        assertEquals(0, new BigDecimal("0.15").compareTo(rule.getStationRate()));
        assertEquals(0, new BigDecimal("0.10").compareTo(rule.getPlatformRate()));
        assertEquals(0, new BigDecimal("0.05").compareTo(rule.getInsuranceRate()));
        assertEquals("ACTIVE", rule.getStatus());

        RevenueSettlement settlement = realPool.createSettlement(
                100L, Instant.now().minus(java.time.Duration.ofDays(1)), Instant.now());
        assertEquals(SettlementStatus.PENDING, settlement.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(settlement.getOwnerShare()));
        assertEquals(0, BigDecimal.ZERO.compareTo(settlement.getPlatformShare()));
        assertTrue(settlement.getSettlementNo().startsWith("SET-"));
    }
}
