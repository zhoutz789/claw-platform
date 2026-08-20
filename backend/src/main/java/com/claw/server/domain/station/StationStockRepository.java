package com.claw.server.domain.station;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StationStockRepository extends JpaRepository<StationStock, Long> {

    Optional<StationStock> findByStationIdAndSkuCode(Long stationId, String skuCode);

    List<StationStock> findByStationIdOrderBySkuCode(Long stationId);
}
