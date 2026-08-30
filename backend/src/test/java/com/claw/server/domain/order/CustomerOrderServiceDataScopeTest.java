package com.claw.server.domain.order;

import com.claw.server.common.dto.OrderDtos.CustomerOrderView;
import com.claw.server.common.enums.OrderStatus;
import com.claw.server.common.security.DataScopeContext;
import com.claw.server.common.security.DataScopeResult;
import com.claw.server.common.security.DataScopeResult.Scope;
import com.claw.server.domain.deposit.DepositRuleService;
import com.claw.server.domain.deposit.DepositService;
import com.claw.server.domain.role.DataScopeService;
import com.claw.server.domain.sharedpool.SharedPoolService;
import com.claw.server.test.scope.CriteriaProbeHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CustomerOrderService#list(Long, String)} 的数据范围接线测试（权限通电 P1-T03 决策③a）。
 *
 * <p>与资产域同构：验证 ALL 上下文下不过滤、非 ALL 上下文下经 {@code DataScopeSpec} 构造谓词，
 * 且任何情况下都不回落到 {@code dataScopeService.resolve(...)}（回落会绕过切面语义、
 * 使开发态放开失效）。订单实体不直带 departmentId，DEPARTMENT/CUSTOM 走 owner 子查询，
 * 其正确性由 {@code DataScopeSpecTest} 的子查询用例覆盖。
 */
@ExtendWith(MockitoExtension.class)
class CustomerOrderServiceDataScopeTest {

    @Mock
    private CustomerOrderRepository orderRepository;
    @Mock
    private CustomerOrderItemRepository orderItemRepository;
    @Mock
    private DepositService depositService;
    @Mock
    private DepositRuleService depositRuleService;
    @Mock
    private SharedPoolService sharedPoolService;
    @Mock
    private UnitRegistrationService unitRegistrationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private DataScopeService dataScopeService;

    @InjectMocks
    private CustomerOrderService customerOrderService;

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    @DisplayName("上下文为 ALL → 两笔订单全部返回，且不回落 resolve()")
    void allScope_returnsEveryOrder() {
        when(orderRepository.findAll(any(Specification.class))).thenReturn(twoOrders());
        DataScopeContext.set(DataScopeResult.all());

        List<CustomerOrderView> views = customerOrderService.list(null, null);

        assertEquals(2, views.size(), "ALL 不应过滤掉任何订单");
        assertEquals(List.of(100L, 101L), views.stream().map(CustomerOrderView::id).toList(),
                "应原样返回仓储给出的两笔订单");
        verify(dataScopeService, never()).resolve(any());
    }

    @Test
    @DisplayName("上下文为 ALL → 传给仓储的 Specification 不含过滤谓词")
    void allScope_passesNonFilteringSpecification() {
        when(orderRepository.findAll(any(Specification.class))).thenReturn(twoOrders());
        DataScopeContext.set(DataScopeResult.all());

        customerOrderService.list(null, null);

        ArgumentCaptor<Specification<CustomerOrder>> captor = specCaptor();
        verify(orderRepository).findAll(captor.capture());

        try (CriteriaProbeHarness harness = CriteriaProbeHarness.bootstrap()) {
            var predicate = harness.translate(captor.getValue());
            assertNotNull(predicate, "ALL 分支应给出 conjunction");
            assertTrue(predicate.getExpressions().isEmpty(), "ALL 分支不应含任何过滤子表达式");
        }
    }

    @Test
    @DisplayName("上下文为非 ALL（SELF）→ 经 DataScopeSpec 构造谓词，绝不回落 resolve()")
    void selfScope_buildsSpecWithoutFallbackResolve() {
        when(orderRepository.findAll(any(Specification.class))).thenReturn(twoOrders());
        DataScopeContext.set(new DataScopeResult(Scope.SELF, null, Set.of(), Set.of(), Set.of(), 42L));

        List<CustomerOrderView> views = customerOrderService.list(null, null);

        assertEquals(2, views.size(), "仓储被 mock，返回条数由 mock 决定；重点是不抛异常且走了 Spec 路径");
        verify(dataScopeService, never()).resolve(any());
        verify(orderRepository).findAll(any(Specification.class));
    }

    @Test
    @DisplayName("上下文为非 ALL（TYPE）→ 构造出非空谓词且不回落 resolve()")
    void typeScope_buildsNonEmptyPredicate() {
        when(orderRepository.findAll(any(Specification.class))).thenReturn(twoOrders());
        DataScopeContext.set(new DataScopeResult(
                Scope.TYPE, null, Set.of("VEHICLE"), Set.of(), Set.of(), 42L));

        customerOrderService.list(null, null);

        ArgumentCaptor<Specification<CustomerOrder>> captor = specCaptor();
        verify(orderRepository).findAll(captor.capture());
        assertNotNull(captor.getValue(), "TYPE 范围必须构造出 Specification");
        verify(dataScopeService, never()).resolve(any());
    }

    @Test
    @DisplayName("软删订单被排除；buyerUserId 入参在数据范围之上叠加过滤")
    void deletedOrdersExcludedAndBuyerFilterStacks() {
        CustomerOrder deleted = order(102L, "ORD-102", 7L);
        deleted.setDeleted(true);
        when(orderRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(twoOrders().get(0), twoOrders().get(1), deleted));
        DataScopeContext.set(DataScopeResult.all());

        List<CustomerOrderView> all = customerOrderService.list(null, null);
        assertEquals(2, all.size(), "软删订单必须被排除");

        DataScopeContext.set(DataScopeResult.all());
        List<CustomerOrderView> byBuyer = customerOrderService.list(42L, null);
        assertEquals(1, byBuyer.size(), "buyerUserId=42 只应命中一笔");
        assertEquals(100L, byBuyer.get(0).id(), "应命中买家 42 的订单");
    }

    // ---------- 工具方法 ----------

    private static List<CustomerOrder> twoOrders() {
        return List.of(order(100L, "ORD-100", 42L), order(101L, "ORD-101", 99L));
    }

    private static CustomerOrder order(Long id, String orderNo, Long buyerUserId) {
        return CustomerOrder.builder()
                .id(id)
                .orderNo(orderNo)
                .buyerUserId(buyerUserId)
                .assetType("VEHICLE")
                .status(OrderStatus.CREATED)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Specification<CustomerOrder>> specCaptor() {
        return ArgumentCaptor.forClass(Specification.class);
    }
}
