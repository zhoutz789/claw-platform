package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 无人机机型产品类仓储（对应 claw.drone_product_classes）。
 */
public interface DroneProductClassRepository extends JpaRepository<DroneProductClass, Long> {

    Optional<DroneProductClass> findByCode(String code);

    /** 按作业场景过滤机型（scenario 为 {@code DroneScenario} 名或 GENERAL）。 */
    List<DroneProductClass> findByScenario(String scenario);
}
