package com.claw.server.domain.clearing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 差错挂账工单仓储（claw.suspense_entry）。
 */
public interface SuspenseEntryRepository extends JpaRepository<SuspenseEntry, Long> {

    Optional<SuspenseEntry> findByEntryNo(String entryNo);

    List<SuspenseEntry> findByStatusOrderByCreatedAtAsc(String status);

    List<SuspenseEntry> findByReconRunId(Long reconRunId);
}
