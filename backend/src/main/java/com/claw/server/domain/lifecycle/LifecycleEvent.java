package com.claw.server.domain.lifecycle;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** 流通链事件流（设备全生命周期责任可追溯，权威历史）。对应 claw.lifecycle_events。 */
@Entity
@Table(name = "lifecycle_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LifecycleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    /** PRODUCE/CERTIFY/SHIP/RECEIVE/TRANSFER_OUT/TRANSFER_IN/PICKUP/DEPLOY/RECALL/RETURN。 */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "station_id")
    private Long stationId;

    @Column(name = "order_ref")
    private String orderRef;

    @Column(name = "custody_ref")
    private Long custodyRef;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
