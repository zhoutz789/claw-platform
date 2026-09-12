package com.claw.server.domain.autonomy;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * 无人车安全事件（越界/失联/低电量/障碍物/人工急停），类比无人机锁机禁行。
 * 对应 claw.autonomy_safety_events（V121）。
 */
@Entity
@Table(name = "autonomy_safety_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutonomySafetyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /** GEOFENCE / LOST_LINK / LOW_BATTERY / OBSTACLE / E_STOP */
    @Column(nullable = false)
    private String cause;

    @Builder.Default
    @Column(nullable = false)
    private String severity = "WARN"; // WARN / CRITICAL

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detailJson;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
