package com.claw.server.domain.compliance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplianceCheckRepository extends JpaRepository<ComplianceCheck, Long> {

    List<ComplianceCheck> findByUserIdOrderByCreatedAtDesc(Long userId);
}
