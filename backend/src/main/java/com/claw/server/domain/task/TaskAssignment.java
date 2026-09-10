package com.claw.server.domain.task;

import com.claw.server.common.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 任务接单（对应 claw.task_assignments）。
 * 一个任务可被同一 provider 接一次；provider 通过 asset 履约。
 */
@Entity
@Table(name = "task_assignments", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "asset_id")
    private Long assetId;

    @Column(name = "project_id")
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TaskStatus status = TaskStatus.ASSIGNED;

    @Column(name = "progress_pct", nullable = false)
    @Builder.Default
    private Integer progressPct = 0;

    @Column(name = "last_progress_note", length = 255)
    private String lastProgressNote;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
