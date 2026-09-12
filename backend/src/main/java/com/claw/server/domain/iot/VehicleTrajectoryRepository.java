package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 车辆轨迹仓储（对应 claw.vehicle_trajectory）。
 * 按资产 + 时间窗取轨迹（升序）、取最新点（里程退役判定）、统计点数。
 */
public interface VehicleTrajectoryRepository extends JpaRepository<VehicleTrajectory, Long> {

    /** 某资产在 [from, to] 区间内的轨迹点，按时刻升序。 */
    List<VehicleTrajectory> findByAssetIdAndTBetweenOrderByTAsc(Long assetId, Instant from, Instant to);

    /** 某资产最新一个轨迹点（t 最大）。 */
    Optional<VehicleTrajectory> findTopByAssetIdOrderByTDesc(Long assetId);

    /** 某资产轨迹点总数。 */
    long countByAssetId(Long assetId);
}
