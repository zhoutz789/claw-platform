package com.claw.server.common.event;

/**
 * 取货扫码完成事件（event_type = {@code PICKUP_COMPLETED}）。
 *
 * <p>由 {@code FulfillmentService.pickupScan} 在同一本地事务内写 {@code outbox_events}（payload 由
 * {@code buildPickupPayload} 构建），再经 {@link OutboxRelay} 认领并同步投递给
 * {@link OutboxHandler}。本 record 是 payload 的<b>类型化视图</b>，仅含基本类型，
 * <b>不引入任何 domain 依赖</b>，满足 ArchUnit「common 不得依赖 domain」边界。
 *
 * <p><b>V86 字段对齐</b>：原声明的 {@code fulfillmentOrderItemId} 与实际 payload 完全对不上
 * （payload 写的是 {@code deviceId}），且该 record 从未被任何代码使用，属于「看起来对、实际错」的
 * 死代码。现已按 {@code FulfillmentService:370-390} 的真实 payload 对齐为
 * {@code orderId / stationId / customerUserId / manufacturerId / deviceId / assetId}。
 *
 * <p><b>{@code 0L} 伪值的处理</b>：{@code buildPickupPayload} 在 {@code assetId}/{@code deviceId}
 * 缺失时填 {@code 0L}（历史逻辑，本次不动 {@code FulfillmentService} 的业务代码）。
 * 紧凑构造器统一把 {@code 0L} 归一成 {@code null}，使「缺资产」与「资产 id=0」可区分。
 *
 * <p><b>消费约定</b>：金额、订单状态、冻结额一律以 DB 为准，本 record 的字段只用于日志与
 * {@code source_event_id} 追溯，<b>不得用 payload 里的金额参与计算</b>（防重放脏数据）。
 */
public record PickupCompletedEvent(
        long orderId,
        Long stationId,
        Long customerUserId,
        Long manufacturerId,
        Long deviceId,
        Long assetId) {

    /** 归一：把历史 payload 里代表「缺失」的 {@code 0L} 伪值统一转成 {@code null}。 */
    public PickupCompletedEvent {
        deviceId = zeroToNull(deviceId);
        assetId = zeroToNull(assetId);
        stationId = zeroToNull(stationId);
        customerUserId = zeroToNull(customerUserId);
        manufacturerId = zeroToNull(manufacturerId);
    }

    private static Long zeroToNull(Long value) {
        return (value != null && value == 0L) ? null : value;
    }
}
