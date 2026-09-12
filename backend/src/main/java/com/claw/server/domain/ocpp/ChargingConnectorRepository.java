package com.claw.server.domain.ocpp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChargingConnectorRepository extends JpaRepository<ChargingConnector, Long> {
    List<ChargingConnector> findByStationId(String stationId);

    Optional<ChargingConnector> findByStationIdAndConnectorId(String stationId, int connectorId);
}
