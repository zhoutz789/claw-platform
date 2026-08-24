package com.claw.server.domain.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface AssetLifecycleEventRepository extends JpaRepository<AssetLifecycleEvent, Long>,
        JpaSpecificationExecutor<AssetLifecycleEvent> {
    List<AssetLifecycleEvent> findByAssetIdOrderByOccurredAtDesc(Long assetId);
}
