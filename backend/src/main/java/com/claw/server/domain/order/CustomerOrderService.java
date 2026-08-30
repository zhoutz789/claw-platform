package com.claw.server.domain.order;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.DepositRequests;
import com.claw.server.common.dto.OrderDtos.CreateCustomerOrderReq;
import com.claw.server.common.dto.OrderDtos.CustomerOrderItemView;
import com.claw.server.common.dto.OrderDtos.CustomerOrderView;
import com.claw.server.common.dto.OrderDtos.ChooseModeReq;
import com.claw.server.common.dto.OrderDtos.OrderLineReq;
import com.claw.server.common.dto.OrderDtos.PayReq;
import com.claw.server.common.enums.OrderStatus;
import com.claw.server.common.enums.UsageMode;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.DataScope;
import com.claw.server.common.security.DataScopeContext;
import com.claw.server.common.security.DataScopeFieldMapping;
import com.claw.server.common.security.DataScopeResult;
import com.claw.server.common.security.DataScopeSpec;
import com.claw.server.domain.deposit.DepositRuleService;
import com.claw.server.domain.deposit.DepositService;
import com.claw.server.domain.order.event.OrderCompletedEvent;
import com.claw.server.domain.order.event.OrderPaidEvent;
import com.claw.server.domain.role.DataScopeService;
import com.claw.server.domain.sharedpool.SharedPoolService;
import com.claw.server.domain.user.Department;
import com.claw.server.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 客户订单服务：创建 / 支付 / 选模式 / 发货 / 完成 + 状态机守卫 + 建产权 + 冻押金 + 发事件。
 *
 * <p>V38 变更：
 * <ul>
 *   <li>{@link #create} 改按 sku+qty 建订单头 + N 个订单项，不再要求下单传 assetId
 *       （历史 assetId 非空路径保留兼容）；</li>
 *   <li>{@link #pay} 仅对历史单资产订单（assetId 非空）建产权；多资产订单的产权在
 *       「登记生成资产」时建立（UnitRegistrationService）；pay 仍发 OrderPaidEvent（携带 assetIds）；</li>
 *   <li>{@link #ship} 增加登记守卫：新多资产订单须每项 REGISTERED 数 == quantity 才放行。</li>
 * </ul>
 *
 * <p>跨域协作只经服务接口与领域事件（不直持他域 Repository，遵守订单域边界）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerOrderService {

    private final CustomerOrderRepository orderRepository;
    private final CustomerOrderItemRepository orderItemRepository;
    private final DepositService depositService;
    private final DepositRuleService depositRuleService;
    private final SharedPoolService sharedPoolService;
    private final UnitRegistrationService unitRegistrationService;
    private final ApplicationEventPublisher eventPublisher;
    private final DataScopeService dataScopeService;

    /** 创建客户订单（CREATED），写订单 + 订单项。 */
    @Transactional
    public CustomerOrderView create(CreateCustomerOrderReq req) {
        if (req.buyerUserId() == null) {
            throw BizException.invalidParam("error.order.buyer.required");
        }

        CustomerOrder order = CustomerOrder.builder()
                .orderNo(generateOrderNo())
                .buyerUserId(req.buyerUserId())
                .productId(req.productId())
                .assetId(req.assetId())       // 新流程为 null；旧单资产直购有值
                .assetType(req.assetType())
                .usageMode(UsageMode.SELF)
                .status(OrderStatus.CREATED)
                .totalAmount(BigDecimal.ZERO)
                .depositAmount(BigDecimal.ZERO)
                .stationId(req.stationId())
                .tenantId(1L)
                .deleted(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        order = orderRepository.save(order);

        if (req.lines() != null && !req.lines().isEmpty()) {
            // 多 SKU 下单：每个 line 建一个订单项（资产在登记时逐台产生，assetId 留空）
            for (OrderLineReq line : req.lines()) {
                int qty = line.quantity() == null ? 1 : line.quantity();
                BigDecimal unitPrice = line.unitPrice() == null ? BigDecimal.ZERO : line.unitPrice();
                orderItemRepository.save(CustomerOrderItem.builder()
                        .orderId(order.getId())
                        .assetId(null)
                        .skuId(line.skuId())
                        .assetType(line.assetType())
                        .quantity(qty)
                        .unitPrice(unitPrice)
                        .subtotal(unitPrice.multiply(BigDecimal.valueOf(qty)))
                        .tenantId(1L)
                        .createdAt(Instant.now())
                        .build());
            }
        } else {
            // 旧单资产直购：1 个订单项 + quantity（默认 1）
            int qty = req.quantity() == null ? 1 : req.quantity();
            BigDecimal unitPrice = req.unitPrice() == null ? BigDecimal.ZERO : req.unitPrice();
            orderItemRepository.save(CustomerOrderItem.builder()
                    .orderId(order.getId())
                    .assetId(req.assetId())
                    .skuId(req.skuId())
                    .assetType(req.assetType())
                    .quantity(qty)
                    .unitPrice(unitPrice)
                    .subtotal(unitPrice.multiply(BigDecimal.valueOf(qty)))
                    .tenantId(1L)
                    .createdAt(Instant.now())
                    .build());
        }

        log.info("创建客户订单 orderNo={} buyer={} lines={}", order.getOrderNo(), req.buyerUserId(),
                req.lines() != null ? req.lines().size() : 0);
        return toView(order);
    }

    /** 支付：冻押金(台账) + 置 PAID + 发 OrderPaidEvent（携带 assetIds）。
     *  <p>产权：仅历史单资产订单（assetId 非空）在支付时建产权；V38 多资产订单的产权在登记生成资产时建立。 */
    @Transactional
    public CustomerOrderView pay(Long orderId, PayReq req) {
        CustomerOrder order = load(orderId);
        requireStatus(order, OrderStatus.CREATED);

        BigDecimal total = req.paidAmount() != null && req.paidAmount().compareTo(BigDecimal.ZERO) > 0
                ? req.paidAmount() : order.getTotalAmount();
        order.setTotalAmount(total);

        // 阶梯押金（首年 rate；查不到默认 0.30）
        BigDecimal depositAmount = depositRuleService.computeDepositAmount(total, order.getAssetType(), 0);
        order.setDepositAmount(depositAmount);

        // 产权：历史单资产直购订单在此建产权（FULL）；多资产订单跳过（登记时建）
        if (order.getAssetId() != null) {
            sharedPoolService.establishOwnership(order.getAssetId(), order.getBuyerUserId(), total, req.payOrderNo());
        }

        // 冻押金（只动台账，真实资金直进托管 = 资金不过站 R2）
        DepositRequests.Hold hold = new DepositRequests.Hold(
                order.getBuyerUserId(), order.getAssetId(), depositAmount, req.payOrderNo());
        String depositNo = depositService.hold(hold).depositNo();
        order.setDepositNo(depositNo);
        order.setPayOrderNo(req.payOrderNo());

        order.setStatus(OrderStatus.PAID);
        order.setUpdatedAt(Instant.now());
        order = orderRepository.save(order);

        List<Long> assetIds = order.getAssetId() != null ? List.of(order.getAssetId()) : List.of();
        eventPublisher.publishEvent(new OrderPaidEvent(
                order.getId(), order.getAssetId(), order.getBuyerUserId(),
                order.getStationId(), order.getUsageMode().name(), assetIds));
        log.info("客户订单支付 orderNo={} deposit={} depositNo={}", order.getOrderNo(), depositAmount, depositNo);
        return toView(order);
    }

    /**
     * 选择使用模式（PAID 后）：SHARED 必带 stationId。
     * 切到 SHARED 时重发 OrderPaidEvent，以便监听器按最新模式重新评估入池资格。
     */
    @Transactional
    public CustomerOrderView chooseMode(Long orderId, ChooseModeReq req) {
        CustomerOrder order = load(orderId);
        requireStatus(order, OrderStatus.PAID);

        UsageMode mode = UsageMode.valueOf(req.usageMode());
        if (mode == UsageMode.SHARED && req.stationId() == null) {
            throw BizException.invalidParam("error.order.shared.station.required");
        }
        order.setUsageMode(mode);
        if (req.stationId() != null) {
            order.setStationId(req.stationId());
        }
        order.setUpdatedAt(Instant.now());
        order = orderRepository.save(order);

        if (mode == UsageMode.SHARED) {
            List<Long> assetIds = order.getAssetId() != null ? List.of(order.getAssetId()) : List.of();
            eventPublisher.publishEvent(new OrderPaidEvent(
                    order.getId(), order.getAssetId(), order.getBuyerUserId(),
                    order.getStationId(), order.getUsageMode().name(), assetIds));
        }
        return toView(order);
    }

    /** 发货（PAID/CERTIFICATED → SHIPPED）。新多资产订单须登记齐全（守卫）。 */
    @Transactional
    public CustomerOrderView ship(Long orderId) {
        CustomerOrder order = load(orderId);
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.CERTIFICATED) {
            throw BizException.of(40970, "error.order.status.ship");
        }
        // V38 登记守卫：新多资产订单（assetId 为空）须逐项登记齐全才放行；
        // 旧单资产直购订单（assetId 非空）无逐台登记模型，跳过守卫（兼容）。
        if (order.getAssetId() == null) {
            for (CustomerOrderItem item : orderItemRepository.findByOrderId(orderId)) {
                long registered = unitRegistrationService.countRegistered(item.getId());
                if (registered != item.getQuantity()) {
                    throw BizException.of(40971, "error.order.registration.incomplete",
                            item.getSkuId(), registered, item.getQuantity());
                }
            }
        }
        order.setStatus(OrderStatus.SHIPPED);
        order.setShippedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        order = orderRepository.save(order);
        log.info("客户订单发货 orderNo={}", order.getOrderNo());
        return toView(order);
    }

    /** 完成（SHIPPED → COMPLETED）+ 发 OrderCompletedEvent。 */
    @Transactional
    public CustomerOrderView complete(Long orderId) {
        CustomerOrder order = load(orderId);
        requireStatus(order, OrderStatus.SHIPPED);
        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        order = orderRepository.save(order);

        eventPublisher.publishEvent(new OrderCompletedEvent(order.getId(), order.getPoolEntryId()));
        log.info("客户订单完成 orderNo={}", order.getOrderNo());
        return toView(order);
    }

    @Transactional(readOnly = true)
    public List<CustomerOrderItemView> listItems(Long orderId) {
        return orderItemRepository.findByOrderId(orderId).stream()
                .map(this::toItemView)
                .toList();
    }

    @DataScope(entity = "customer_order")
    @Transactional(readOnly = true)
    public List<CustomerOrderView> list(Long buyerUserId, String status) {
        // 数据范围 enforcement（P1-T03）：DataScopeAspect 写入 DataScopeContext；
        // 非经切面进入时降级直接解析，保证过滤不丢。
        DataScopeResult ds = DataScopeContext.get();
        if (ds == null) {
            ds = dataScopeService.resolve(AuthContext.currentUserId());
        }
        Specification<CustomerOrder> spec = (ds == null || ds.isAll())
                ? (root, q, cb) -> cb.conjunction()
                : DataScopeSpec.of(DataScopeFieldMapping.of("buyerUserId", null, null, "assetType",
                        User.class, Department.class)).apply(ds);
        List<CustomerOrder> all = orderRepository.findAll(spec).stream()
                .filter(o -> !Boolean.TRUE.equals(o.getDeleted()))
                .toList();
        if (buyerUserId != null) {
            all = all.stream().filter(o -> buyerUserId.equals(o.getBuyerUserId())).toList();
        }
        if (status != null) {
            all = all.stream().filter(o -> status.equals(o.getStatus().name())).toList();
        }
        return all.stream().map(this::toView).toList();
    }

    private CustomerOrder load(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("error.order.not.found"));
    }

    private void requireStatus(CustomerOrder o, OrderStatus expected) {
        if (o.getStatus() != expected) {
            throw BizException.of(40970, "error.order.status");
        }
    }

    private String generateOrderNo() {
        String date = LocalDate.now().toString().replace("-", "");
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "ORD-" + date + "-" + rand;
    }

    CustomerOrderView toView(CustomerOrder o) {
        return new CustomerOrderView(o.getId(), o.getOrderNo(), o.getBuyerUserId(), o.getProductId(),
                o.getAssetId(), o.getAssetType(),
                o.getUsageMode() == null ? null : o.getUsageMode().name(),
                o.getStatus() == null ? null : o.getStatus().name(),
                o.getTotalAmount(), o.getDepositAmount(), o.getDepositNo(), o.getPayOrderNo(),
                o.getStationId(), o.getPoolEntryId(), o.getSplitRuleId(), o.getCertificateId(),
                o.getShippedAt(), o.getCompletedAt(), o.getCancelReason(), o.getRefundStatus(),
                o.getCreatedAt());
    }

    private CustomerOrderItemView toItemView(CustomerOrderItem i) {
        return new CustomerOrderItemView(i.getId(), i.getOrderId(), i.getAssetId(), i.getSkuId(),
                i.getAssetType(), i.getQuantity(), i.getUnitPrice(), i.getSubtotal());
    }
}
