package com.claw.server.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;

/** 物流任务扩展仓储（claw.task_logistics）。 */
public interface TaskLogisticsRepository extends JpaRepository<TaskLogistics, Long> {
}
