package com.claw.server.domain.autonomy;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * 无人车自主任务（AUTO_DELIVERY / AUTO_SWEEP / AUTO_PATROL）。
 * 对应 claw.autonomy_tasks（V121）。
 */
@Entity
@Table(name = "autonomy_tasks", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutonomyTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /** DELIVERY / SWEEP / PATROL */
    @Column(nullable = false)
    private String subtype;

    @Builder.Default
    @Column(nullable = false)
    private String status = "PENDING";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String pathJson;

    @Column(name = "current_waypoint")
    private Integer currentWaypoint;

    @Builder.Default
    @Column(name = "progress_pct", nullable = false)
    private Integer progressPct = 0;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
