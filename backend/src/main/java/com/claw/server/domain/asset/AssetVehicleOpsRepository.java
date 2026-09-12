package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;

public interface AssetVehicleOpsRepository extends JpaRepository<AssetVehicleOps, Long>,
        JpaSpecificationExecutor<AssetVehicleOps> {
    List<AssetVehicleOps> findByAssetIdOrderByStartedAtDesc(Long assetId);

    /** 取某资产在 [from, to] 时间窗内（按 startedAt）的运营记录，用于收益报表聚合。 */
    List<AssetVehicleOps> findByAssetIdAndStartedAtBetween(Long assetId, Instant from, Instant to);
}
