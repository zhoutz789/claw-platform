package com.claw.server.domain.order.event;

import com.claw.server.common.enums.UsageMode;
import com.claw.server.domain.order.CustomerOrder;
import com.claw.server.domain.order.CustomerOrderRepository;
import com.claw.server.domain.sharedpool.SharedPoolEntry;
import com.claw.server.domain.sharedpool.SharedPoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 订单 → 共享池接线（事件驱动）：消费 {@link OrderPaidEvent}（AFTER_COMMIT），
 * 若订单为 SHARED 且尚未入池，则经 {@link SharedPoolService} 入池 + 建分成规则，
 * 并回写 poolEntryId / splitRuleId（用本域 CustomerOrderRepository，不直接持 sharedpool 仓储）。
 *
 * <p>注意：本方法本身不再标 @Transactional（AFTER_COMMIT 已无外围事务，
 * 内部 SharedPoolService 各方法自带事务）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSharedPoolIntegration {

    private static final BigDecimal OWNER_RATE = new BigDecimal("0.70");
    private static final BigDecimal STATION_RATE = new BigDecimal("0.15");

    private final SharedPoolService sharedPoolService;
    private final CustomerOrderRepository orderRepository;

    @TransactionalEventListener
    public void onOrderPaid(OrderPaidEvent event) {
        CustomerOrder order = orderRepository.findById(event.getOrderId()).orElse(null);
        if (order == null) {
            log.warn("OrderSharedPoolIntegration: 订单不存在 orderId={}", event.getOrderId());
            return;
        }
        // 仅 SHARED 且尚未入池时处理
        if (order.getUsageMode() != UsageMode.SHARED || order.getPoolEntryId() != null) {
            return;
        }

        // V38：携带资产列表（多资产订单逐台入池）；为空则回退 order.assetId（兼容旧单资产直购）。
        // 新多资产订单在 pay 时资产尚未登记（assetIds 空且 order.assetId 空）→ 入池顺延到
        // AssetProvisionedIntegration（登记生成资产后触发）。
        List<Long> assetIds = event.getAssetIds();
        if (assetIds == null || assetIds.isEmpty()) {
            if (order.getAssetId() != null) {
                assetIds = List.of(order.getAssetId());
            } else {
                log.info("OrderSharedPoolIntegration: 多资产订单资产尚未登记，入池顺延 orderId={}", order.getId());
                return;
            }
        }

        Long stationId = order.getStationId();
        if (stationId == null) {
            log.warn("OrderSharedPoolIntegration: SHARED 订单缺 stationId，跳过入池 orderId={}", order.getId());
            return;
        }

        for (Long assetId : assetIds) {
            SharedPoolEntry entry = sharedPoolService.poolAsset(
                    assetId, order.getBuyerUserId(), stationId,
                    OWNER_RATE, STATION_RATE, BigDecimal.ZERO, BigDecimal.ZERO);
            Long splitRuleId = sharedPoolService.createSplitRule(
                    assetId, entry.getId(), OWNER_RATE, STATION_RATE);
            // 多资产订单仅有单一 poolEntryId 字段，记录最后一笔（首笔已满足兼容）
            order.setPoolEntryId(entry.getId());
            order.setSplitRuleId(splitRuleId);
        }
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
        log.info("订单入池完成 orderId={} assets={} poolEntryId={} splitRuleId={}",
                order.getId(), assetIds, order.getPoolEntryId(), order.getSplitRuleId());
    }
}
