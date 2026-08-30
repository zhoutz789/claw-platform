package com.claw.server.domain.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 事务发件箱事件（对应 V50 claw.outbox_events）。
 * 与业务变更同事务写入，转发器异步发布到 {@code ApplicationEventPublisher}，订阅方按事件幂等消费。
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

    @Column(nullable = false, length = 40)
    private String aggregateType;

    @Column(nullable = false)
    private Long aggregateId;

    @Column(nullable = false, length = 40)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payloadJson;

    @Builder.Default
    private Boolean published = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant publishedAt;
}
