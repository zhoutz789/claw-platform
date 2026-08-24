package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface AssetMaintenanceRecordRepository extends JpaRepository<AssetMaintenanceRecord, Long>,
        JpaSpecificationExecutor<AssetMaintenanceRecord> {
    List<AssetMaintenanceRecord> findByAssetIdOrderByServicedAtDesc(Long assetId);
}
