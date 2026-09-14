package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 无人机机型场景属性仓储（对应 claw.drone_scenario_attrs）。
 */
public interface DroneScenarioAttrRepository extends JpaRepository<DroneScenarioAttr, Long> {

    List<DroneScenarioAttr> findByProductClassId(Long productClassId);
}
