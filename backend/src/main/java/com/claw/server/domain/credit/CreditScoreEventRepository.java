package com.claw.server.domain.credit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditScoreEventRepository extends JpaRepository<CreditScoreEvent, Long> {
    List<CreditScoreEvent> findByUserIdOrderByCreatedAtDesc(Long userId);
}
