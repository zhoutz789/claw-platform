package com.claw.server.domain.operator;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperatorBondRepository extends JpaRepository<OperatorBond, Long> {

    List<OperatorBond> findByOperatorIdAndDeletedFalse(Long operatorId);

    List<OperatorBond> findByStationIdAndDeletedFalse(Long stationId);
}
