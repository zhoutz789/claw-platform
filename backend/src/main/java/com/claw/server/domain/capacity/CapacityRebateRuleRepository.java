package com.claw.server.domain.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CapacityRebateRuleRepository extends JpaRepository<CapacityRebateRule, Long> {

    Optional<CapacityRebateRule> findFirstByPlanIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(Long planId, String status);

    List<CapacityRebateRule> findByPlanIdAndDeletedFalse(Long planId);
}
