package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DroneRepository extends JpaRepository<Drone, Long> {
    Optional<Drone> findByAssetId(Long assetId);
}
