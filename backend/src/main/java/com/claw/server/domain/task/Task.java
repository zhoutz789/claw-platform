package com.claw.server.domain.task;

import com.claw.server.common.enums.AssetCapability;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.common.enums.TaskType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 任务主表（对应 claw.tasks）。
 * 发布 → 接单 → 进度 → 完成 → 结算 全状态机；仅 LOGISTICS 在 P0 走完整闭环。
 */
@Entity
@Table(name = "tasks", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "publisher_id", nullable = false)
    private Long publisherId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private TaskType taskType;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "reward_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal rewardAmount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "capability_required", nullable = false, length = 30)
    private AssetCapability capabilityRequired;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TaskStatus status = TaskStatus.OPEN;

    @Column(name = "geo_lat", precision = 10, scale = 8)
    private BigDecimal geoLat;

    @Column(name = "geo_lng", precision = 11, scale = 8)
    private BigDecimal geoLng;

    @Column(name = "service_radius_m")
    private Integer serviceRadiusM;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;
}
