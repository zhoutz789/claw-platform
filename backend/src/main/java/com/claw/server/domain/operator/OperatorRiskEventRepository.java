package com.claw.server.domain.operator;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperatorRiskEventRepository extends JpaRepository<OperatorRiskEvent, Long> {

    List<OperatorRiskEvent> findByOperatorIdAndDeletedFalse(Long operatorId);

    List<OperatorRiskEvent> findByResolvedFalseAndDeletedFalse();
}
