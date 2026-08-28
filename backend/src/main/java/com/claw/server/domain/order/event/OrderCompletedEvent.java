package com.claw.server.domain.order.event;

/**
 * 订单完成领域事件：由 {@code CustomerOrderService.complete} 发布，
 * {@code OrderSettlementIntegration} 在事务提交后（AFTER_COMMIT）消费，触发自动分账。
 */
public class OrderCompletedEvent {

    private final Long orderId;
    private final Long poolEntryId;

    public OrderCompletedEvent(Long orderId, Long poolEntryId) {
        this.orderId = orderId;
        this.poolEntryId = poolEntryId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getPoolEntryId() {
        return poolEntryId;
    }
}
