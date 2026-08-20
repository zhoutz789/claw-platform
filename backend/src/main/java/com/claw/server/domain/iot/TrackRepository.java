package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TrackRepository extends JpaRepository<Track, Long> {
    List<Track> findByAssetIdAndTsBetweenOrderByTsAsc(Long assetId, Instant from, Instant to);
}
