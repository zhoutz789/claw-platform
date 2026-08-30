package com.claw.server.domain.inventory;

import com.claw.server.common.enums.LifecycleEventType;
import com.claw.server.common.enums.LifecycleStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 流通链事件流（对应 V48 claw.lifecycle_events）。每次状态变更写一条，支持责任倒查（R4）。
 */
@Entity
@Table(name = "lifecycle_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Enumerated(EnumType.STRING)
    private LifecycleStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LifecycleStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LifecycleEventType eventType;

    private Long operatorId;

    private Long stationId;

    private String orderRef;

    private Long custodyRef;

    @Column(nullable = false)
    @Builder.Default
    private Instant occurredAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
