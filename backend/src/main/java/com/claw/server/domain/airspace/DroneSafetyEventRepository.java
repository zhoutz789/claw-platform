package com.claw.server.domain.airspace;

import com.claw.server.common.enums.DroneSafetyEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DroneSafetyEventRepository extends JpaRepository<DroneSafetyEvent, Long> {

    List<DroneSafetyEvent> findByAssetIdOrderByCreatedAtDesc(Long assetId);

    List<DroneSafetyEvent> findByAssetIdAndStatus(Long assetId, DroneSafetyEventStatus status);

    boolean existsByAssetIdAndStatus(Long assetId, DroneSafetyEventStatus status);
}
