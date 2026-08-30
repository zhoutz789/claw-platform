package com.claw.server.domain.settlement;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.FulfillmentStatus;
import com.claw.server.common.enums.SettleStep;
import com.claw.server.common.event.DomainEvent;
import com.claw.server.domain.commission.CommissionRuleService;
import com.claw.server.domain.fulfillment.FulfillmentOrder;
import com.claw.server.domain.fulfillment.FulfillmentOrderItem;
import com.claw.server.domain.fulfillment.FulfillmentOrderItemRepository;
import com.claw.server.domain.fulfillment.FulfillmentOrderRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.role.PrincipalBinding;
import com.claw.server.domain.role.PrincipalBindingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 结算服务（增量 B · R7）。
 *
 * <p>订阅 Outbox 转发的 {@code PICKUP_COMPLETED} 领域事件，按 SettleStep 顺序结算：
 * ① LOGISTICS 物流费（寄售模式厂家到站物流已计入调拨单，履约单记 0）② COMMISSION 服务站提成
 * ③ BALANCE_TO_MFG 余额归厂家（释放用户冻结）。单步失败挂起转人工（Q6：manual=TRUE）。
 * 本服务通过 ApplicationEvent 解耦于取货扫码的物理交付事务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final FulfillmentOrderRepository fulfillmentOrderRepository;
    private final FulfillmentOrderItemRepository orderItemRepository;
    private final CommissionRuleService commissionRuleService;
    /** 资金域只能通过 AccountService 交互（ArchUnit：资金域仓储不允许跨域访问）。 */
    private final AccountService accountService;
    private final PrincipalBindingRepository bindingRepository;
    private final ObjectMapper objectMapper;

    @EventListener
    @Transactional
    public void onPickupCompleted(DomainEvent event) {
        if (!"PICKUP_COMPLETED".equals(event.getEventType())) {
            return;
        }
        Long orderId = parseOrderId(event.getPayloadJson());
        if (orderId == null) {
            return;
        }
        try {
            runSettlement(orderId);
        } catch (Exception e) {
            log.error("取货订单 {} 结算失败（挂起转人工）: {}", orderId, e.getMessage(), e);
        }
    }

    private Long parseOrderId(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.has("orderId") ? node.get("orderId").asLong() : null;
        } catch (Exception e) {
            log.warn("解析 PICKUP_COMPLETED payload 失败: {}", json);
            return null;
        }
    }

    /** 取货扫码后续结算（R7）：按 SettleStep 顺序推进，单步失败挂起转人工。 */
    @Transactional
    public void runSettlement(Long orderId) {
        FulfillmentOrder order = fulfillmentOrderRepository.findById(orderId)
                .orElseThrow(() -> BizException.of(40401, "fulfillment.order.not.found"));
        if (order.getStatus() == FulfillmentStatus.SETTLED) {
            return; // 幂等
        }
        BigDecimal total = order.getFrozenAmount() != null ? order.getFrozenAmount() : order.getTotalAmount();
        if (total == null) {
            total = BigDecimal.ZERO;
        }

        // ① LOGISTICS：履约单无独立物流费（厂家到站物流已计入调拨单），记 0 留痕
        settleStep(order, SettleStep.LOGISTICS, BigDecimal.ZERO, null, null, false,
                "设备销售履约·物流费（寄售模式厂家到站物流计入调拨单）");

        // ② COMMISSION：服务站提成
        Long stationUserId = resolvePrincipalUser(order.getStationId(), "STATION");
        Long mfgUserId = resolvePrincipalUser(order.getManufacturerId(), "MANUFACTURER");
        Long productId = firstProductId(order);
        BigDecimal commission = commissionRuleService.resolveCommission(order.getManufacturerId(), productId, total);
        Account mfgAcc = mfgUserId != null ? findUserAccount(mfgUserId) : null;
        Account stationAcc = stationUserId != null ? findUserAccount(stationUserId) : null;
        boolean commissionManual = false;
        if (commission.compareTo(BigDecimal.ZERO) > 0) {
            if (mfgAcc != null && stationAcc != null) {
                mfgAcc.setBalance(mfgAcc.getBalance().subtract(commission).max(BigDecimal.ZERO));
                stationAcc.setBalance(stationAcc.getBalance().add(commission));
                accountService.saveAccount(mfgAcc);
                accountService.saveAccount(stationAcc);
            } else {
                commissionManual = true;
            }
        }
        settleStep(order, SettleStep.COMMISSION, commission, mfgAcc, stationAcc, commissionManual,
                commissionManual ? "账户缺失·转人工" : "服务站提成");

        // ③ BALANCE_TO_MFG：余额归厂家（释放用户冻结）
        BigDecimal toMfg = total.subtract(commission).max(BigDecimal.ZERO);
        Account customerAcc = findUserAccount(order.getCustomerUserId());
        boolean balanceManual = false;
        if (customerAcc != null && mfgAcc != null) {
            customerAcc.setFrozen(customerAcc.getFrozen().subtract(toMfg).max(BigDecimal.ZERO));
            customerAcc.setBalance(customerAcc.getBalance().subtract(toMfg).max(BigDecimal.ZERO));
            mfgAcc.setBalance(mfgAcc.getBalance().add(toMfg));
            accountService.saveAccount(customerAcc);
            accountService.saveAccount(mfgAcc);
        } else {
            balanceManual = true;
        }
        settleStep(order, SettleStep.BALANCE_TO_MFG, toMfg, customerAcc, mfgAcc, balanceManual,
                balanceManual ? "账户缺失·转人工" : "余额归厂家");

        order.setStatus(FulfillmentStatus.SETTLED);
        order.setSettledAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        fulfillmentOrderRepository.save(order);
        log.info("取货订单 {} 结算完成 total={} commission={}", orderId, total, commission);
    }

    private void settleStep(FulfillmentOrder order, SettleStep step, BigDecimal amount, Account payer,
                           Account payee, boolean manual, String remark) {
        Settlement s = Settlement.builder()
                .settlementNo("ST" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 16))
                .bizRefType("FULFILLMENT")
                .bizRefId(order.getId())
                .settleStep(step)
                .payerAccountId(payer != null ? payer.getId() : null)
                .payeeAccountId(payee != null ? payee.getId() : null)
                .amount(amount)
                .currency("USD")
                .status(manual ? "MANUAL" : "SETTLED")
                .manual(manual)
                .remark(remark)
                .settledAt(manual ? null : Instant.now())
                .build();
        settlementRepository.save(s);
    }

    private Long resolvePrincipalUser(Long principalId, String principalType) {
        if (principalId == null) {
            return null;
        }
        List<PrincipalBinding> b = bindingRepository.findByPrincipalTypeAndPrincipalId(principalType, principalId);
        return b.isEmpty() ? null : b.get(0).getUserId();
    }

    private Account findUserAccount(Long userId) {
        if (userId == null) {
            return null;
        }
        return accountService.findSubAccount(userId, AccountType.SUB).orElse(null);
    }

    private Long firstProductId(FulfillmentOrder order) {
        List<FulfillmentOrderItem> items = orderItemRepository.findByFulfillmentOrderId(order.getId());
        return items.isEmpty() ? null : items.get(0).getProductId();
    }
}
