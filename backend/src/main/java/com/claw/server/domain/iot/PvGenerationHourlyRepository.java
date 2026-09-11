package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PvGenerationHourlyRepository extends JpaRepository<PvGenerationHourly, Long> {

    /** 幂等定位：同一设备 + 同一小时桶 + 同一来源 只有一行。 */
    Optional<PvGenerationHourly> findByDeviceNoAndBucketAtAndSource(String deviceNo, Instant bucketAt, String source);

    /** 站点维度小时电量查询（倒序取最近 N 小时）。 */
    List<PvGenerationHourly> findByStationAssetIdAndBucketAtBetweenOrderByBucketAtDesc(
            Long stationAssetId, Instant from, Instant to);

    /** 单台设备的累计电量（按来源隔离，绝不跨 source 求和）。 */
    @Query("select coalesce(sum(h.energyWh), 0) from PvGenerationHourly h "
            + "where h.deviceNo = :deviceNo and h.source = :source")
    BigDecimal sumEnergyWhByDeviceNoAndSource(@Param("deviceNo") String deviceNo,
                                              @Param("source") String source);

    /** 批量设备的累计电量（避免逐台查询的 N+1）；返回 [deviceNo, sum(energyWh)]。 */
    @Query("select h.deviceNo, sum(h.energyWh) from PvGenerationHourly h "
            + "where h.deviceNo in :deviceNos and h.source = :source group by h.deviceNo")
    List<Object[]> sumEnergyWhGroupByDeviceNo(@Param("deviceNos") Collection<String> deviceNos,
                                              @Param("source") String source);
}
