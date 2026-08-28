package com.claw.server.domain.order;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.AssetRequests.ProvisionAssetReq;
import com.claw.server.common.dto.DepositViews;
import com.claw.server.common.dto.OrderDtos.*;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.OrderStatus;
import com.claw.server.common.enums.UsageMode;
import com.claw.server.domain.asset.AssetService;
import com.claw.server.domain.order.event.AssetProvisionedEvent;
import com.claw.server.domain.sharedpool.SharedPoolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订单逐台登记 + 发货守卫 单测（Mockito，无真实数据库）。
 * 覆盖：① ship 登记守卫（40971）；② 登记生成资产且 orderItemId 可溯源；③ pay 产权时机分支；④ 兼容旧单资产。
 */
@ExtendWith(MockitoExtension.class)
class OrderRegistrationTest {

    @Mock private CustomerOrderRepository orderRepository;
    @Mock private CustomerOrderItemRepository orderItemRepository;
    @Mock private com.claw.server.domain.deposit.DepositService depositService;
    @Mock private com.claw.server.domain.deposit.DepositRuleService depositRuleService;
    @Mock private SharedPoolService sharedPoolService;
    @Mock private UnitRegistrationService unitRegistrationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private CustomerOrderService orderService;

    private CustomerOrder newOrder(OrderStatus status, Long assetId) {
        CustomerOrder o = new CustomerOrder();
        o.setId(1L);
        o.setOrderNo("ORD-TEST");
        o.setBuyerUserId(5L);
        o.setAssetId(assetId);
        o.setAssetType("VEHICLE");
        o.setUsageMode(UsageMode.SELF);
        o.setStatus(status);
        o.setTotalAmount(BigDecimal.ZERO);
        o.setTenantId(1L);
        o.setDeleted(false);
        return o;
    }

    private CustomerOrderItem item(Long id, int qty) {
        CustomerOrderItem i = new CustomerOrderItem();
        i.setId(id);
        i.setOrderId(1L);
        i.setSkuId(5L);
        i.setAssetType("VEHICLE");
        i.setQuantity(qty);
        i.setTenantId(1L);
        return i;
    }

    // ===== ① ship 登记守卫（40971）=====

    @Test
    void ship_rejects_when_registration_incomplete() {
        CustomerOrder order = newOrder(OrderStatus.PAID, null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        CustomerOrderItem it = item(10L, 2);
        when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(it));
        when(unitRegistrationService.countRegistered(10L)).thenReturn(1L); // 仅 1/2

        BizException ex = assertThrows(BizException.class, () -> orderService.ship(1L));
        assertEquals(40971, ex.getCode());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void ship_ok_when_registration_complete() {
        CustomerOrder order = newOrder(OrderStatus.PAID, null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        CustomerOrderItem it = item(10L, 2);
        when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of(it));
        when(unitRegistrationService.countRegistered(10L)).thenReturn(2L);
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerOrderView v = orderService.ship(1L);
        assertEquals("SHIPPED", v.status());
    }

    // ===== ④ 兼容旧单资产（assetId 非空）跳过守卫 =====

    @Test
    void ship_legacy_assetId_skips_guard() {
        CustomerOrder order = newOrder(OrderStatus.PAID, 10L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerOrderView v = orderService.ship(1L);
        assertEquals("SHIPPED", v.status());
        // 旧单资产订单不经过登记守卫：unitRegistrationService 不应被调用
        verify(unitRegistrationService, never()).countRegistered(anyLong());
    }

    // ===== pay 产权时机分支 =====

    @Test
    void pay_new_order_does_not_build_ownership() {
        CustomerOrder order = newOrder(OrderStatus.CREATED, null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(depositRuleService.computeDepositAmount(any(), any(), anyInt()))
                .thenReturn(new BigDecimal("0.30"));
        when(depositService.hold(any())).thenReturn(new DepositViews.DepositView(
                1L, "DEP", 5L, null, BigDecimal.ZERO, "HELD", "PO1", null, Instant.now(), Instant.now()));

        orderService.pay(1L, new PayReq("PO1", new BigDecimal("100000.0000")));
        verify(sharedPoolService, never()).establishOwnership(anyLong(), anyLong(), any(), any());
    }

    @Test
    void pay_legacy_order_builds_ownership() {
        CustomerOrder order = newOrder(OrderStatus.CREATED, 10L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(depositRuleService.computeDepositAmount(any(), any(), anyInt()))
                .thenReturn(new BigDecimal("0.30"));
        when(depositService.hold(any())).thenReturn(new DepositViews.DepositView(
                1L, "DEP", 5L, 10L, BigDecimal.ZERO, "HELD", "PO1", null, Instant.now(), Instant.now()));

        orderService.pay(1L, new PayReq("PO1", new BigDecimal("100000.0000")));
        verify(sharedPoolService).establishOwnership(eq(10L), eq(5L), any(), eq("PO1"));
    }

    // ===== ② 登记生成资产且 orderItemId 可溯源 =====

    @Test
    void register_units_provisions_asset_with_order_item_id() {
        // 装配独立的 UnitRegistrationService（与 orderService 隔离）
        UnitRegistrationRepository regRepo = mock(UnitRegistrationRepository.class);
        AssetService assetService = mock(AssetService.class);
        SharedPoolService sharedPool = mock(SharedPoolService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        UnitRegistrationService svc = new UnitRegistrationService(
                regRepo, orderRepository, orderItemRepository, assetService, sharedPool, publisher);

        CustomerOrderItem it = item(10L, 2);
        when(orderItemRepository.findById(10L)).thenReturn(Optional.of(it));
        CustomerOrder order = newOrder(OrderStatus.PAID, null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(regRepo.findByOrderItemId(10L)).thenReturn(List.of());
        when(assetService.provisionFromRegistration(any(), anyLong()))
                .thenAnswer(inv -> new ApiViews.AssetView(99L, AssetType.VEHICLE, "ASSET-1", "QR1",
                        "SN1", 1L, 2L, 3L, 5L, null, AssetStatus.IN_STOCK, Instant.now()));
        when(sharedPool.establishOwnership(anyLong(), anyLong(), any(), any())).thenReturn(null);
        when(regRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UnitRegistrationReq u = new UnitRegistrationReq("QR1", "V1", "F1", "M1", "SN1",
                "[{\"type\":\"BATTERY\",\"no\":\"B1\"}]", null, null, null, "X1", null);
        List<UnitRegistrationView> views = svc.registerUnits(10L, new UnitRegistrationBatchReq(List.of(u)), 1L);

        ArgumentCaptor<ProvisionAssetReq> cap = ArgumentCaptor.forClass(ProvisionAssetReq.class);
        verify(assetService).provisionFromRegistration(cap.capture(), eq(1L));
        ProvisionAssetReq p = cap.getValue();
        assertEquals(10L, p.orderItemId());
        assertEquals(5L, p.ownerId());           // 来自 order.buyerUserId
        assertEquals(5L, p.skuId());
        assertEquals("VEHICLE", p.assetType());
        assertEquals("QR1", p.qrCode());

        verify(sharedPool).establishOwnership(eq(99L), eq(5L), any(), any());
        verify(regRepo).save(argThat(r -> r.getAssetId().equals(99L)
                && r.getStatus() == UnitRegistration.UnitRegistrationStatus.REGISTERED));
        ArgumentCaptor<AssetProvisionedEvent> evCap =
                ArgumentCaptor.forClass(AssetProvisionedEvent.class);
        verify(publisher).publishEvent(evCap.capture());
        assertEquals(99L, evCap.getValue().getAssetId());
        assertEquals(10L, evCap.getValue().getOrderItemId());
        assertEquals("QR1", evCap.getValue().getQrCode());
        assertEquals(1, views.size());
    }
}
