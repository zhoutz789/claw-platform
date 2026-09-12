package com.claw.server.domain.iot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 车辆轨迹服务：落库轨迹点、按资产 + 时间窗取轨迹、取最新点。
 *
 * <p>最新点（{@link #getLatest}）被资产生命周期扫描器用于读取累计里程，
 * 作为里程退役判定的依据；缺失轨迹时回落里程 0。
 */
@Service
@RequiredArgsConstructor
public class VehicleTrajectoryService {

    private final VehicleTrajectoryRepository repository;

    /**
     * 记录一个车辆轨迹点（t 取当前时刻）。
     *
     * @param assetId     资产 ID
     * @param lat         纬度（可空）
     * @param lng         经度（可空）
     * @param speedKph    速度 km/h（可空）
     * @param heading     航向角（度，可空）
     * @param odometerKm  累计里程 km（可空 → 落 0）
     * @param soc         电量百分比 0-100（可空）
     * @return 落库后的轨迹点
     */
    public VehicleTrajectory recordPoint(Long assetId, Double lat, Double lng, Double speedKph,
                                         Double heading, Long odometerKm, Double soc) {
        VehicleTrajectory point = VehicleTrajectory.builder()
                .assetId(assetId)
                .t(Instant.now())
                .lat(lat)
                .lng(lng)
                .speedKph(speedKph)
                .heading(heading)
                .odometerKm(odometerKm != null ? odometerKm : 0L)
                .soc(soc)
                .build();
        return repository.save(point);
    }

    /** 取某资产在 [from, to] 区间内的轨迹点（按时刻升序）。 */
    public List<VehicleTrajectory> getTrajectory(Long assetId, Instant from, Instant to) {
        return repository.findByAssetIdAndTBetweenOrderByTAsc(assetId, from, to);
    }

    /** 取某资产最新一个轨迹点（用于里程退役判定等）。 */
    public Optional<VehicleTrajectory> getLatest(Long assetId) {
        return repository.findTopByAssetIdOrderByTDesc(assetId);
    }
}
