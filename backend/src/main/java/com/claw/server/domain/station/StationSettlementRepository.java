package com.claw.server.domain.station;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 服务站寄售结算单仓储（模块四 · 结算层）。 */
public interface StationSettlementRepository extends JpaRepository<StationSettlement, Long> {

    List<StationSettlement> findByStationIdOrderByCreatedAtDesc(Long stationId);

    List<StationSettlement> findByStationIdInOrderByCreatedAtDesc(List<Long> stationIds);
}
