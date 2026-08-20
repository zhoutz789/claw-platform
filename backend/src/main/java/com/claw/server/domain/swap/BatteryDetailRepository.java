package com.claw.server.domain.swap;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BatteryDetailRepository extends JpaRepository<BatteryDetail, Long> {
    Optional<BatteryDetail> findByAssetId(Long assetId);
}
