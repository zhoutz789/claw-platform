package com.claw.server.common.event;

import org.springframework.context.ApplicationEvent;

/**
 * 领域事件（应用内广播信封）。
 *
 * <p><b>V86 起 {@link OutboxRelay} 不再发布本类</b>：relay 改为按 {@code event_type} 精确路由并
 * 同步调用 {@link OutboxHandler}。原因是消费者若用 {@code @TransactionalEventListener}
 * （默认 AFTER_COMMIT），监听器抛异常时 relay 事务已提交、{@code published=true} 已落库，
 * 异常只被 Spring 记一行日志 → <b>事件永久丢失且无任何痕迹</b>。
 *
 * <p>本类现仅作为「应用内非资金类广播」的通用信封保留，与 outbox 是两套并存机制：
 * <ul>
 *   <li><b>outbox 链路</b>（{@link OutboxPublisher} → {@link OutboxRelay} → {@link OutboxHandler}）：
 *       落库、可重试、有死信，用于<b>必须不丢</b>的事件（如 {@code PICKUP_COMPLETED} 结算）；</li>
 *   <li><b>本类链路</b>（业务代码直接 {@code ApplicationEventPublisher.publishEvent}）：
 *       不落库、无重试，用于<b>丢了可接受</b>的联动（如风控/生命周期/资产开通通知）。</li>
 * </ul>
 * 需要可靠性保障的新事件请走 outbox，不要用本信封。
 */
public class DomainEvent extends ApplicationEvent {

    private final String aggregateType;
    private final Long aggregateId;
    private final String eventType;
    private final String payloadJson;

    public DomainEvent(String aggregateType, Long aggregateId, String eventType, String payloadJson) {
        super(aggregateType + ":" + aggregateId + ":" + eventType);
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }
}
