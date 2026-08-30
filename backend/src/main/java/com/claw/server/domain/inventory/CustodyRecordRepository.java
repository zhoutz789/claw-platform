package com.claw.server.domain.inventory;

import com.claw.server.common.enums.CustodyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustodyRecordRepository extends JpaRepository<CustodyRecord, Long> {

    Optional<CustodyRecord> findByAssetIdAndStatus(Long assetId, CustodyStatus status);

    List<CustodyRecord> findByStationIdAndStatus(Long stationId, CustodyStatus status);

    List<CustodyRecord> findByTransferOrderId(Long transferOrderId);
}
