package com.claw.server.domain.swap;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 订单事件流（对应 claw.order_events）。
 * 技术文档 2.3：状态机每次流转写事件，可回溯。
 */
@Entity
@Table(name = "order_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String orderNo;

    @Column(nullable = false, length = 32)
    private String event;

    private Long operatorId;

    private String payload;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
