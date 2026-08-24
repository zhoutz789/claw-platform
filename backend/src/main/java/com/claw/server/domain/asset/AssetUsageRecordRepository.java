package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface AssetUsageRecordRepository extends JpaRepository<AssetUsageRecord, Long>,
        JpaSpecificationExecutor<AssetUsageRecord> {
    List<AssetUsageRecord> findByAssetIdOrderByPeriodStartDesc(Long assetId);
}
