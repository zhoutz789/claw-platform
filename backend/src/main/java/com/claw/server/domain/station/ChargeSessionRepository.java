package com.claw.server.domain.station;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChargeSessionRepository extends JpaRepository<ChargeSession, Long> {

    List<ChargeSession> findByAssetIdOrderByStartedAtDesc(Long assetId);

    List<ChargeSession> findByStationIdOrderByStartedAtDesc(Long stationId);

    Optional<ChargeSession> findTopByAssetIdAndStatusOrderByStartedAtDesc(Long assetId, String status);

    /** 按充电桩设备号查活跃会话（VPP 切片：充电桩可削减量按活跃会话功率统计）。 */
    List<ChargeSession> findByDeviceNoAndStatus(String deviceNo, String status);
}
