package com.claw.server.common.dto;

import com.claw.server.common.enums.AssetCapability;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.common.enums.TaskType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 任务大厅视图（task 出参，仅依赖 common 层）。
 *
 * <p>映射逻辑刻意置于 {@code com.claw.server.domain.task} 服务层（如 TaskService.toTaskView），
 * 本类不持有任何 domain 实体引用，以满足架构守护
 * {@code commonLayerMustNotDependOnDomains}（common 层不得反向依赖 domain）。
 */
public final class TaskViews {

    private TaskViews() {
    }

    /** 任务视图。LOGISTICS 任务附带 logistics 明细 map（其余为 null）。 */
    public record TaskView(
            Long id,
            Long publisherId,
            TaskType taskType,
            String title,
            String description,
            BigDecimal rewardAmount,
            String currency,
            AssetCapability capabilityRequired,
            TaskStatus status,
            BigDecimal geoLat,
            BigDecimal geoLng,
            Integer serviceRadiusM,
            Instant createdAt,
            Instant deadlineAt,
            Instant assignedAt,
            Instant completedAt,
            Instant settledAt,
            Map<String, Object> logistics) {
    }

    /** 接单视图。 */
    public record AssignmentView(
            Long id,
            Long taskId,
            Long providerId,
            Long assetId,
            TaskStatus status,
            Integer progressPct,
            String lastProgressNote,
            Instant startedAt,
            Instant finishedAt) {
    }

    /** 资产收益视图（从账本 C 方向分录反查）。 */
    public record TaskEarningView(
            Long taskId,
            Long assignmentId,
            Long assetId,
            BigDecimal amount,
            String bizRef,
            String memo,
            Instant createdAt) {
    }

    /** 我的任务（发布 + 已接）。 */
    public record MyTasksView(
            List<TaskView> published,
            List<AssignmentView> accepted) {
    }
}
