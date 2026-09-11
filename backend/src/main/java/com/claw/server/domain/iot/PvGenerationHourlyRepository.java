package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PvGenerationHourlyRepository extends JpaRepository<PvGenerationHourly, Long> {

    /** 幂等定位：同一设备 + 同一小时桶 + 同一来源 只有一行。 */
    Optional<PvGenerationHourly> findByDeviceNoAndBucketAtAndSource(String deviceNo, Instant bucketAt, String source);

    /** 站点维度小时电量查询（倒序取最近 N 小时）。 */
    List<PvGenerationHourly> findByStationAssetIdAndBucketAtBetweenOrderByBucketAtDesc(
            Long stationAssetId, Instant from, Instant to);
}
