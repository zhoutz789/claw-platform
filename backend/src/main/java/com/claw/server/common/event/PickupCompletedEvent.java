package com.claw.server.common.event;

/**
 * 取货扫码完成领域事件（增量 B · R6 结算触发）。
 *
 * <p>由 {@code FulfillmentService.pickupScan} 在同一本地事务内写 {@code outbox_events} 后，经 Outbox 转发器
 * 发布到 Spring {@code ApplicationEventPublisher}；{@code SettlementSubscriber} 异步消费并按 R7 顺序结算。
 *
 * <p>本事件仅含基本类型字段，<b>不引入任何 domain 依赖</b>，满足 ArchUnit「common 不得依赖 domain」边界。
 * 订阅方按 {@code orderId} 做幂等去重。
 */
public record PickupCompletedEvent(
        long orderId,
        long assetId,
        long stationId,
        long customerUserId,
        long manufacturerId,
        long fulfillmentOrderItemId) {
}
