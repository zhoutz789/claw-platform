package com.claw.server.domain.order.event;

import com.claw.server.domain.sharedpool.SharedPoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;

/**
 * 订单 → 分账接线（事件驱动）：消费 {@link OrderCompletedEvent}（AFTER_COMMIT），
 * 若订单已入池（poolEntryId 非空），经 {@link SharedPoolService} 建首笔 RevenueSettlement(PENDING)。
 *
 * <p>补齐「completeRental 未落 Settlement」的缺口，复用同一分账口径；
 * 后续周期结算可复用 createSettlement 方法。方法本身不再标 @Transactional。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSettlementIntegration {

    private final SharedPoolService sharedPoolService;

    @TransactionalEventListener
    public void onOrderCompleted(OrderCompletedEvent event) {
        Long poolEntryId = event.getPoolEntryId();
        if (poolEntryId == null) {
            log.info("OrderSettlementIntegration: 非共享订单，跳过分账 orderId={}", event.getOrderId());
            return;
        }
        Instant periodEnd = Instant.now();
        Instant periodStart = periodEnd.minus(Duration.ofDays(1));
        sharedPoolService.createSettlement(poolEntryId, periodStart, periodEnd);
        log.info("订单完成触发分账 poolEntryId={} orderId={}", poolEntryId, event.getOrderId());
    }
}
