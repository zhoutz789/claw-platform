package com.claw.server.common.event;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Outbox 事件行（对应 claw.outbox_events）。
 * 与业务变更写入同一本地事务，保证「状态变更」与「事件发布」原子；
 * 由 {@link OutboxRelay} 异步转发到 ApplicationEventPublisher 后标记 published。
 */
@Entity
@Table(name = "outbox_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private Long aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Column(columnDefinition = "text")
    private String payloadJson;

    @Builder.Default
    @Column(nullable = false)
    private boolean published = false;

    private Instant publishedAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
