package com.claw.server.domain.station;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChargeSessionRepository extends JpaRepository<ChargeSession, Long> {

    List<ChargeSession> findByAssetIdOrderByStartedAtDesc(Long assetId);

    List<ChargeSession> findByStationIdOrderByStartedAtDesc(Long stationId);

    Optional<ChargeSession> findTopByAssetIdAndStatusOrderByStartedAtDesc(Long assetId, String status);
}
