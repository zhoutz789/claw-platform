package com.claw.server.domain.task;

import com.claw.server.common.enums.AssetCapability;
import com.claw.server.common.enums.TaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 任务仓储（claw.tasks）。 */
public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * 悲观写锁查询任务行（accept 并发安全修复）。
     *
     * <p>在 {@link TaskService#accept(Long, Long, Long)} 事务内使用，确保「状态检查 → 建接单 → 置 ASSIGNED」
     * 对任务行加排他锁串行化，防止并发接单产生重复 assignment（一任务双结）。
     * 写法完全照搬 {@code AccountRepository#findByIdForUpdate}，SELECT ... FOR UPDATE 在 PostgreSQL 下生效。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") Long id);

    /** 发布者视角的任务列表（逻辑未删除）。 */
    List<Task> findByPublisherIdAndDeletedFalse(Long publisherId);

    /** 按状态 + 所需能力查可接任务（provider 能力匹配）。 */
    List<Task> findByStatusAndCapabilityRequired(TaskStatus status, AssetCapability capabilityRequired);

    /** 按状态查（预留）。 */
    List<Task> findByStatus(TaskStatus status);
}
