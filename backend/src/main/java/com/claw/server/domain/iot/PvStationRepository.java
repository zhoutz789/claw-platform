package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PvStationRepository extends JpaRepository<PvStation, Long> {

    Optional<PvStation> findByAssetId(Long assetId);
}
