package com.claw.server.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {
    Optional<ReconciliationRun> findByRunDate(LocalDate runDate);

    Optional<ReconciliationRun> findFirstByOrderByRunDateDesc();
}
