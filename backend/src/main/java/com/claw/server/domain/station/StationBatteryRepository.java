package com.claw.server.domain.station;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StationBatteryRepository extends JpaRepository<StationBattery, Long> {
    List<StationBattery> findByStationIdOrderBySlotNoAsc(Long stationId);

    Optional<StationBattery> findByBatteryId(Long batteryId);

    long countByStationIdAndStatus(Long stationId, String status);
}
