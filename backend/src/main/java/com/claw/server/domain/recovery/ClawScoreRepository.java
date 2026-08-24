package com.claw.server.domain.recovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClawScoreRepository extends JpaRepository<ClawScore, Long> {

    Optional<ClawScore> findByUserIdAndDeletedFalse(Long userId);
}
