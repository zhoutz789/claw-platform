package com.claw.server.domain.swap;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ElecPriceSnapshotRepository extends JpaRepository<ElecPriceSnapshot, Long> {
    Optional<ElecPriceSnapshot> findFirstByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(LocalDate date);
}
