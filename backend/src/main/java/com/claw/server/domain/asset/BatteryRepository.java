package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BatteryRepository extends JpaRepository<Battery, Long> {
    Optional<Battery> findByAssetId(Long assetId);
}
