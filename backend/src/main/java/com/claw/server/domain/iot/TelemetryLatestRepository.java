package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelemetryLatestRepository extends JpaRepository<TelemetryLatest, Long> {
    Optional<TelemetryLatest> findByDeviceId(Long deviceId);

    Optional<TelemetryLatest> findByAssetId(Long assetId);
}
