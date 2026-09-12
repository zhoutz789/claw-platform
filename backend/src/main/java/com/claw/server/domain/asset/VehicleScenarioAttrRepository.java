package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VehicleScenarioAttrRepository extends JpaRepository<VehicleScenarioAttr, Long> {
    List<VehicleScenarioAttr> findByProductClassId(Long productClassId);
}
