package com.claw.server.domain.sharedpool;

import com.claw.server.common.enums.PoolEntryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SharedPoolEntryRepository extends JpaRepository<SharedPoolEntry, Long> {

    List<SharedPoolEntry> findByCurrentStationIdAndStatusAndDeletedFalse(Long stationId, PoolEntryStatus status);

    List<SharedPoolEntry> findByOwnerUserIdAndDeletedFalse(Long ownerUserId);

    List<SharedPoolEntry> findByAssetIdAndDeletedFalse(Long assetId);
}
