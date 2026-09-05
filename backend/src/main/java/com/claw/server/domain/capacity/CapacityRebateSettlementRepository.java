package com.claw.server.domain.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CapacityRebateSettlementRepository extends JpaRepository<CapacityRebateSettlement, Long> {

    List<CapacityRebateSettlement> findByPlanIdAndDeletedFalse(Long planId);

    List<CapacityRebateSettlement> findBySubscriberUserIdAndDeletedFalse(Long subscriberUserId);
}
