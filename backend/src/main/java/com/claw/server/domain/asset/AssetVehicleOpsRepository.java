package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface AssetVehicleOpsRepository extends JpaRepository<AssetVehicleOps, Long>,
        JpaSpecificationExecutor<AssetVehicleOps> {
    List<AssetVehicleOps> findByAssetIdOrderByStartedAtDesc(Long assetId);
}
