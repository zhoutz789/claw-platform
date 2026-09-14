package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 无人机航迹仓储（对应 claw.drone_trajectory）。
 * 按资产 + 时间窗取航迹（升序）、取最新点、统计点数。镜像 {@code VehicleTrajectoryRepository}。
 */
public interface DroneTrajectoryRepository extends JpaRepository<DroneTrajectory, Long> {

    /** 某资产在 [from, to] 区间内的航迹点，按时刻升序（回放）。 */
    List<DroneTrajectory> findByAssetIdAndTsBetweenOrderByTsAsc(Long assetId, Instant from, Instant to);

    /** 某资产最新一个航迹点（ts 最大）。 */
    Optional<DroneTrajectory> findTopByAssetIdOrderByTsDesc(Long assetId);

    /** 某资产航迹点总数。 */
    long countByAssetId(Long assetId);
}
