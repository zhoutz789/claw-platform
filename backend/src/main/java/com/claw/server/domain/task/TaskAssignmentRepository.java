package com.claw.server.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 任务接单仓储（claw.task_assignments）。 */
public interface TaskAssignmentRepository extends JpaRepository<TaskAssignment, Long> {

    /** 任务 + provider 唯一接单关系。 */
    Optional<TaskAssignment> findByTaskIdAndProviderId(Long taskId, Long providerId);

    /** provider 已接的全部任务。 */
    List<TaskAssignment> findByProviderId(Long providerId);

    /** 资产维度接单（收益对账用）。 */
    List<TaskAssignment> findByAssetId(Long assetId);
}
