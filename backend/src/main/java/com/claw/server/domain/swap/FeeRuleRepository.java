package com.claw.server.domain.swap;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FeeRuleRepository extends JpaRepository<FeeRule, Long> {
    Optional<FeeRule> findByRuleCodeAndStatus(String ruleCode, String status);
}
