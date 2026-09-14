package com.claw.server.domain.iot;

import com.claw.server.common.api.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 无人机航迹服务：写入航迹点、按资产 + 时间窗取航迹回放。
 * 镜像 {@link VehicleTrajectoryService}；{@code append} 的 {@code ts} 取当前时刻。
 */
@Service
@RequiredArgsConstructor
public class DroneTrajectoryService {

    private final DroneTrajectoryRepository repository;

    /**
     * 航迹点入参（一次上报的飞行状态快照）。
     *
     * @param lat        纬度
     * @param lng        经度
     * @param altM       相对高度（米）
     * @param speedMps   速度（米/秒）
     * @param heading    航向角（度）
     * @param batteryPct 电量百分比(0-100)
     * @param posMode    定位模式：RTK / PPK / GNSS
     * @param source     上报来源：FCU / RTK / EDGE
     * @param flightNo   架次号
     */
    public record TrajectoryPoint(Double lat, Double lng, Double altM, Double speedMps,
                                  Double heading, Double batteryPct, String posMode,
                                  String source, String flightNo) {
    }

    /**
     * 写入一个航迹点（ts 取当前时刻）。
     *
     * @param assetId 无人机资产 ID
     * @param point   航迹点
     * @return 落库后的航迹点
     */
    @Transactional
    public DroneTrajectory append(Long assetId, TrajectoryPoint point) {
        if (point == null) {
            throw BizException.invalidParam("error.drone.trajectory.point.required");
        }
        DroneTrajectory t = DroneTrajectory.builder()
                .assetId(assetId)
                .ts(Instant.now())
                .lat(point.lat())
                .lng(point.lng())
                .altM(point.altM())
                .speedMps(point.speedMps())
                .heading(point.heading())
                .batteryPct(point.batteryPct())
                .posMode(point.posMode())
                .source(point.source())
                .flightNo(point.flightNo())
                .build();
        return repository.save(t);
    }

    /** 取某资产在 [from, to] 区间内的航迹点（按时刻升序，供回放）。 */
    @Transactional(readOnly = true)
    public List<DroneTrajectory> query(Long assetId, Instant from, Instant to) {
        return repository.findByAssetIdAndTsBetweenOrderByTsAsc(assetId, from, to);
    }
}
