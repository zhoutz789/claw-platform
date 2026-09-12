package com.claw.server.domain.ocpp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChargingStationRepository extends JpaRepository<ChargingStation, String> {
    Optional<ChargingStation> findByChargePointId(String chargePointId);

    Optional<ChargingStation> findByAssetId(Long assetId);
}
