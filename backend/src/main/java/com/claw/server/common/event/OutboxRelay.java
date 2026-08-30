package com.claw.server.common.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Outbox 转发器：定时轮询未发布事件，发布为 {@link DomainEvent} 并标记已发布。
 * 单实例用 @Scheduled 轮询即可；多实例部署可加 ShedLock（可选，见设计 §6）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository outboxRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void relay() {
        for (OutboxEvent e : outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()) {
            try {
                eventPublisher.publishEvent(new DomainEvent(
                        e.getAggregateType(), e.getAggregateId(), e.getEventType(), e.getPayloadJson()));
                e.setPublished(true);
                e.setPublishedAt(Instant.now());
                outboxRepository.save(e);
            } catch (Exception ex) {
                log.warn("outbox relay failed eventId={}: {}", e.getId(), ex.getMessage());
            }
        }
    }
}
