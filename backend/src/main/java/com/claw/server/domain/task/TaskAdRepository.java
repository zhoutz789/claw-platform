package com.claw.server.domain.task;

import org.springframework.data.jpa.repository.JpaRepository;

/** 广告任务扩展仓储（claw.task_ad）。 */
public interface TaskAdRepository extends JpaRepository<TaskAd, Long> {
}
