package com.claw.server.common.event;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** Outbox 事件仓库（common 层，不依赖任何 domain 包）。 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();
}
