package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetStatusLogRepository extends JpaRepository<AssetStatusLog, Long> {
    List<AssetStatusLog> findByAssetIdOrderByCreatedAtDesc(Long assetId);
}
