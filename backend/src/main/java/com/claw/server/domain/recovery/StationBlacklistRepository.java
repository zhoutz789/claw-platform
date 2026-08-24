package com.claw.server.domain.recovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StationBlacklistRepository extends JpaRepository<StationBlacklist, Long> {

    List<StationBlacklist> findByUserIdAndResolvedFalseAndDeletedFalse(Long userId);

    List<StationBlacklist> findByStationIdAndResolvedFalseAndDeletedFalse(Long stationId);
}
