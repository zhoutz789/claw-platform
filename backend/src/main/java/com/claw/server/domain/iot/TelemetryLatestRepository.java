package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelemetryLatestRepository extends JpaRepository<TelemetryLatest, Long> {
    Optional<TelemetryLatest> findByDeviceId(Long deviceId);

    /**
     * 取该资产最新一条遥测：资产可挂多台设备、每台设备各有一条记录，
     * 故必须按上报时间取 Top1（同刻用 id 兜底，保证确定性）。
     */
    Optional<TelemetryLatest> findTopByAssetIdOrderByReportedAtDescIdDesc(Long assetId);
}
