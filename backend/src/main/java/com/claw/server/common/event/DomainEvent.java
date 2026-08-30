package com.claw.server.common.event;

import org.springframework.context.ApplicationEvent;

/**
 * 领域事件（应用内，经 Transactional Outbox 异步转发）。
 * 由 {@link OutboxRelay} 从 outbox_events 表轮询后通过 ApplicationEventPublisher 发布，
 * 各订阅方（结算 / 通知 / 回收监控）按 eventType 幂等消费。
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
