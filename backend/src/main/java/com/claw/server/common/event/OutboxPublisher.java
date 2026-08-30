package com.claw.server.common.event;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 发布器：在业务事务内（MANDATORY）写入 outbox_events，保证与业务变更原子。
 * 由 {@link OutboxRelay} 异步转发为 {@link DomainEvent}。
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, Long aggregateId, String eventType, String payloadJson) {
        outboxRepository.save(OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payloadJson(payloadJson)
                .build());
    }
}
