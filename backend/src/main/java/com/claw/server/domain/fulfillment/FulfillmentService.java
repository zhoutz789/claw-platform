package com.claw.server.domain.fulfillment;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.CustodyStatus;
import com.claw.server.common.enums.FulfillmentStatus;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.event.OutboxPublisher;
import com.claw.server.domain.consignment.ConsignmentCustody;
import com.claw.server.domain.consignment.ConsignmentCustodyRepository;
import com.claw.server.domain.inventory.Inventory;
import com.claw.server.domain.inventory.InventoryRepository;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.lifecycle.LifecycleEvent;
import com.claw.server.domain.lifecycle.LifecycleEventRepository;
import com.claw.server.domain.project.DeviceAuthorization;
import com.claw.server.domain.project.DeviceAuthorizationRepository;
import com.claw.server.domain.role.PrincipalBinding;
import com.claw.server.domain.role.PrincipalBindingRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 待履约订单服务（增量 B · R6 核心）。
 *
 * <p>用户付款即冻结（frozen_amount，复用 ledger 冻结列），取货扫码履约（PICKUP）才进入结算（R7）。
 * 取货扫码遵循「物理交付与结算解耦」：同一本地事务内完成①扣减服务站寄售占有权②建立用户项目设备授权
 * ③绑定订单明细到具体设备④写 outbox（PICKUP_COMPLETED）；结算由 Outbox 异步触发（Q6：失败挂起转人工）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FulfillmentService {

    private final FulfillmentOrderRepository orderRepository;
    private final FulfillmentOrderItemRepository orderItemRepository;
    private final ConsignmentCustodyRepository custodyRepository;
    private final InventoryRepository inventoryRepository;
    private final DeviceRepository deviceRepository;
    /** 资金域只能通过 AccountService 交互（ArchUnit：资金域仓储不允许跨域访问）。 */
    private final AccountService accountService;
    private final LifecycleEventRepository lifecycleRepository;
    private final DeviceAuthorizationRepository deviceAuthorizationRepository;
    private final PrincipalBindingRepository bindingRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public FulfillmentOrder createOrder(Long customerUserId, Long manufacturerId, Long stationId,
                                       boolean remoteOrder, List<FulfillmentOrderItem> items,
                                       BigDecimal totalAmount) {
        FulfillmentOrder o = FulfillmentOrder.builder()
                .orderNo("FO" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 16))
                .customerUserId(customerUserId)
                .manufacturerId(manufacturerId)
                .stationId(stationId)
                .remoteOrder(remoteOrder)
                .status(FulfillmentStatus.PENDING_PAYMENT)
                .totalAmount(totalAmount)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        o = orderRepository.save(o);
        for (FulfillmentOrderItem it : items) {
            it.setFulfillmentOrderId(o.getId());
            if (it.getQty() == null) {
                it.setQty(1);
            }
            orderItemRepository.save(it);
        }
        return o;
    }

    /** 付款：冻结资金（复用 ledger 冻结列），设置履约超时时点（FULFILL_TIMEOUT_DAYS，Q1）。 */
    @Transactional
    public FulfillmentOrder payOrder(Long orderId, String paymentRef, Long userId) {
        FulfillmentOrder o = load(orderId);
        if (o.getStatus() != FulfillmentStatus.PENDING_PAYMENT) {
            throw BizException.of(40915, "order.not.payable");
        }
        o.setStatus(FulfillmentStatus.PAID_FROZEN);
        o.setFrozenAmount(o.getTotalAmount());
        o.setPaymentRef(paymentRef);
        o.setPaidAt(Instant.now());
        int days = readConfigInt("FULFILL_TIMEOUT_DAYS", 90);
        o.setExpireAt(Instant.now().plus(Duration.ofDays(days)));
        o.setUpdatedAt(Instant.now());
        o = orderRepository.save(o);
        freezeFunds(o.getCustomerUserId(), o.getTotalAmount());
        return o;
    }

    @Transactional
    public FulfillmentOrder confirm(Long orderId) {
        FulfillmentOrder o = load(orderId);
        if (o.getStatus() != FulfillmentStatus.PAID_FROZEN) {
            throw BizException.of(40915, "order.not.confirmable");
        }
        o.setStatus(FulfillmentStatus.CONFIRMED);
        o.setConfirmedAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return orderRepository.save(o);
    }

    @Transactional
    public FulfillmentOrder ship(Long orderId) {
        FulfillmentOrder o = load(orderId);
        if (o.getStatus() != FulfillmentStatus.CONFIRMED) {
            throw BizException.of(40915, "order.not.shippable");
        }
        o.setStatus(FulfillmentStatus.SHIPPED);
        o.setShippedAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return orderRepository.save(o);
    }

    @Transactional
    public FulfillmentOrder receive(Long orderId) {
        FulfillmentOrder o = load(orderId);
        if (o.getStatus() != FulfillmentStatus.SHIPPED) {
            throw BizException.of(40915, "order.not.receivable");
        }
        o.setStatus(FulfillmentStatus.RECEIVED);
        o.setReceivedAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return orderRepository.save(o);
    }

    /**
     * 取货扫码履约（R6 核心）。同一本地事务内完成物理交付，并写 outbox 触发异步结算。
     * 结算失败不影响交付（Q6：挂起转人工）。
     */
    @Transactional
    public FulfillmentOrder pickupScan(Long orderId, List<Long> deviceIds, Long operatorId) {
        FulfillmentOrder o = load(orderId);
        if (o.getStatus() != FulfillmentStatus.RECEIVED && o.getStatus() != FulfillmentStatus.SHIPPED) {
            throw BizException.of(40916, "order.not.pickable");
        }
        List<FulfillmentOrderItem> items = orderItemRepository.findByFulfillmentOrderId(orderId);
        int idx = 0;
        for (Long deviceId : deviceIds) {
            ConsignmentCustody custody = custodyRepository.findByDeviceId(deviceId)
                    .orElseThrow(() -> BizException.of(40401, "custody.not.found"));
            if (custody.getStatus() != CustodyStatus.ACTIVE) {
                throw BizException.of(40917, "custody.not.active");
            }
            if (!o.getStationId().equals(custody.getHolderStationId())) {
                throw BizException.of(40918, "custody.not.at.station");
            }
            custody.setStatus(CustodyStatus.RELEASED);
            custody.setEndedAt(Instant.now());
            custody.setEndedReason("PICKED_UP");
            custodyRepository.save(custody);

            inventoryRepository.findByDeviceId(deviceId).ifPresent(inv -> {
                inv.setCurrentStatus(LifecycleStatus.IN_USER_PROJECT);
                inv.setUpdatedAt(Instant.now());
                inventoryRepository.save(inv);
            });
            Device dev = deviceRepository.findById(deviceId).orElse(null);
            if (dev != null) {
                dev.setLifecycleStatus(LifecycleStatus.IN_USER_PROJECT.name());
                deviceRepository.save(dev);
                // 建立用户项目设备授权（AUTHORIZE）：厂家/平台授权用户使用权
                if (dev.getAssetId() != null) {
                    Long grantor = resolvePrincipalUser(o.getManufacturerId(), "MANUFACTURER");
                    deviceAuthorizationRepository.save(DeviceAuthorization.builder()
                            .assetId(dev.getAssetId())
                            .grantorUserId(grantor != null ? grantor : 0L)
                            .granteeUserId(o.getCustomerUserId())
                            .authType("AUTHORIZE")
                            .scopeJson("[\"USE\"]")
                            .status("ACTIVE")
                            .tenantId(1L)
                            .build());
                }
            }
            if (idx < items.size()) {
                FulfillmentOrderItem it = items.get(idx);
                it.setDeviceId(deviceId);
                orderItemRepository.save(it);
            }
            lifecycleRepository.save(LifecycleEvent.builder()
                    .deviceId(deviceId)
                    .fromStatus(LifecycleStatus.AT_STATION.name())
                    .toStatus(LifecycleStatus.IN_USER_PROJECT.name())
                    .eventType("PICKUP")
                    .operatorId(operatorId)
                    .stationId(o.getStationId())
                    .orderRef(o.getOrderNo())
                    .occurredAt(Instant.now())
                    .build());
            idx++;
        }
        o.setStatus(FulfillmentStatus.PICKED_UP);
        o.setPickedUpAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        o = orderRepository.save(o);
        outboxPublisher.publish("FULFILLMENT_ORDER", o.getId(), "PICKUP_COMPLETED", buildPickupPayload(o, deviceIds));
        log.info("取货扫码完成 order={} devices={}", orderId, deviceIds.size());
        return o;
    }

    @Transactional
    public FulfillmentOrder cancel(Long orderId) {
        FulfillmentOrder o = load(orderId);
        if (o.getStatus() == FulfillmentStatus.SETTLED || o.getStatus() == FulfillmentStatus.PICKED_UP) {
            throw BizException.of(40919, "order.not.cancellable");
        }
        releaseFreeze(o.getCustomerUserId(), o.getFrozenAmount());
        o.setStatus(FulfillmentStatus.CANCELLED);
        o.setUpdatedAt(Instant.now());
        return orderRepository.save(o);
    }

    /** 履约超时：释放冻结，置 EXPIRED（Q1 订单分支）。 */
    @Transactional
    public FulfillmentOrder expire(Long orderId) {
        FulfillmentOrder o = load(orderId);
        if (o.getStatus() != FulfillmentStatus.PAID_FROZEN && o.getStatus() != FulfillmentStatus.PENDING_PAYMENT) {
            return o;
        }
        releaseFreeze(o.getCustomerUserId(), o.getFrozenAmount());
        o.setStatus(FulfillmentStatus.EXPIRED);
        o.setUpdatedAt(Instant.now());
        return orderRepository.save(o);
    }

    @Transactional(readOnly = true)
    public List<FulfillmentOrder> listOrders(Long stationId, Long customerUserId) {
        if (customerUserId != null) {
            return orderRepository.findByCustomerUserId(customerUserId);
        }
        if (stationId != null) {
            return orderRepository.findByStationId(stationId);
        }
        return orderRepository.findAll();
    }

    /** 订单详情：连同 items 明细一并返回（取货扫码页据此挑选待取设备，无需手填 deviceIds）。 */
    @Transactional(readOnly = true)
    public FulfillmentOrder getOrder(Long id) {
        FulfillmentOrder o = load(id);
        o.setItems(orderItemRepository.findByFulfillmentOrderId(id));
        return o;
    }

    private FulfillmentOrder load(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> BizException.of(40401, "fulfillment.order.not.found"));
    }

    /** 复用 ledger 冻结列：冻结用户资金（无外部网关，模拟充值+冻结）。 */
    private void freezeFunds(Long userId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Account acc = accountService.getOrCreateSubAccount(userId, AccountType.SUB);
        acc.setBalance(nz(acc.getBalance()).add(amount));
        acc.setFrozen(nz(acc.getFrozen()).add(amount));
        accountService.saveAccount(acc);
    }

    private void releaseFreeze(Long userId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Account acc = accountService.getOrCreateSubAccount(userId, AccountType.SUB);
        acc.setFrozen(nz(acc.getFrozen()).subtract(amount).max(BigDecimal.ZERO));
        accountService.saveAccount(acc);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private Long resolvePrincipalUser(Long principalId, String principalType) {
        if (principalId == null) {
            return null;
        }
        List<PrincipalBinding> b = bindingRepository.findByPrincipalTypeAndPrincipalId(principalType, principalId);
        return b.isEmpty() ? null : b.get(0).getUserId();
    }

    private String buildPickupPayload(FulfillmentOrder o, List<Long> deviceIds) {
        try {
            Long firstDeviceId = deviceIds.isEmpty() ? null : deviceIds.get(0);
            Long assetId = null;
            if (firstDeviceId != null) {
                Device d = deviceRepository.findById(firstDeviceId).orElse(null);
                if (d != null) {
                    assetId = d.getAssetId();
                }
            }
            return objectMapper.writeValueAsString(Map.of(
                    "orderId", o.getId(),
                    "stationId", o.getStationId(),
                    "customerUserId", o.getCustomerUserId(),
                    "manufacturerId", o.getManufacturerId(),
                    "assetId", assetId == null ? 0L : assetId,
                    "deviceId", firstDeviceId == null ? 0L : firstDeviceId));
        } catch (Exception e) {
            return "{}";
        }
    }

    private int readConfigInt(String key, int fallback) {
        SystemConfig cfg = systemConfigRepository.findByConfigKeyAndDeletedFalse(key).orElse(null);
        if (cfg == null || cfg.getConfigValue() == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(cfg.getConfigValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
