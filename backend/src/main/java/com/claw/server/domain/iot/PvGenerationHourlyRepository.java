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

    /**
     * 单站单日、按来源隔离的小时电量合计（光伏日对账用）。
     * bucket_at 为 UTC 整点，day 边界按 UTC 计算 [start, end)。
     * 返回 coalesce(sum, 0)，无数据时为 0（调用方据此判 GAP）。
     */
    @Query("select coalesce(sum(h.energyWh), 0) from PvGenerationHourly h "
            + "where h.stationAssetId = :stationAssetId and h.source = :source "
            + "and h.bucketAt >= :start and h.bucketAt < :end")
    BigDecimal sumEnergyWhByStationAndSourceAndDay(@Param("stationAssetId") Long stationAssetId,
                                                   @Param("source") String source,
                                                   @Param("start") Instant start,
                                                   @Param("end") Instant end);
}
