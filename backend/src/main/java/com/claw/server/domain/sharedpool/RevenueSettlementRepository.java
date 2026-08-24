package com.claw.server.domain.sharedpool;

import com.claw.server.common.enums.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RevenueSettlementRepository extends JpaRepository<RevenueSettlement, Long> {

    List<RevenueSettlement> findByStationIdAndDeletedFalse(Long stationId);

    List<RevenueSettlement> findByStatusAndDeletedFalse(SettlementStatus status);
}
