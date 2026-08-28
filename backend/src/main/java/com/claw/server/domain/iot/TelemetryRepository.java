package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {
    Optional<Telemetry> findByAssetId(Long assetId);
}
