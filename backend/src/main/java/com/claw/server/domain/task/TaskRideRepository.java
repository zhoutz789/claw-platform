package com.claw.server.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;

/** 出行任务扩展仓储（claw.task_ride）。 */
public interface TaskRideRepository extends JpaRepository<TaskRide, Long> {
}
