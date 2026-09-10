package com.claw.server.domain.task;

import com.claw.server.common.enums.AssetCapability;
import com.claw.server.common.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 任务仓储（claw.tasks）。 */
public interface TaskRepository extends JpaRepository<Task, Long> {

    /** 发布者视角的任务列表（逻辑未删除）。 */
    List<Task> findByPublisherIdAndDeletedFalse(Long publisherId);

    /** 按状态 + 所需能力查可接任务（provider 能力匹配）。 */
    List<Task> findByStatusAndCapabilityRequired(TaskStatus status, AssetCapability capabilityRequired);

    /** 按状态查（预留）。 */
    List<Task> findByStatus(TaskStatus status);
}
