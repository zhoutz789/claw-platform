package com.claw.server.domain.station;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface StationHandoverOrderRepository extends JpaRepository<StationHandoverOrder, Long> {

    List<StationHandoverOrder> findByStationIdOrderByCreatedAtDesc(Long stationId);

    List<StationHandoverOrder> findByStationIdAndCreatedAtBetween(Long stationId, Instant from, Instant to);
}
