package com.claw.server.domain.asset;

import com.claw.server.common.enums.AssetLifecycleStage;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.asset.AssetLifecycleEvent;
import com.claw.server.domain.asset.AssetLifecycleEventRepository;
import com.claw.server.domain.asset.AssetService;
import com.claw.server.domain.asset.BatteryRepository;
import com.claw.server.domain.asset.Drone;
import com.claw.server.domain.asset.DroneRepository;
import com.claw.server.domain.iot.Telemetry;
import com.claw.server.domain.iot.TelemetryRepository;
import com.claw.server.domain.iot.VehicleTrajectory;
import com.claw.server.domain.iot.VehicleTrajectoryRepository;
import com.claw.server.domain.settings.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 资产生命周期扫描器（P0-③ 闭环自动运转）。
 *
 * <p>定时扫描在网资产，按客观指标自动推进闭环终点，无需人工点击：
 * <ul>
 *   <li>退役(RETIRED)：SOH（遥测/电池）< 阈值（默认 70%）或服役年限 > 上限（默认 8 年）→ 结束服务、待残值评估；</li>
 *   <li>无人机另有飞行时长维度：累计飞行 > 上限（默认 3000 分钟）即退役；</li>
 *   <li>回收(RECYCLED)：退役满宽限期（默认 30 天）未复用 → 进入残值/梯次利用流程。</li>
 * </ul>
 *
 * <p>阈值集中在此，便于后续外置到配置中心。扫描器仅调用 {@link AssetService#autoTransition}，
 * 所有流转仍走状态机校验 + 审计，保证与手工操作一致的闭环纪律。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssetLifecycleScheduler {

    private final AssetRepository assetRepository;
    private final BatteryRepository batteryRepository;
    private final DroneRepository droneRepository;
    private final TelemetryRepository telemetryRepository;
    private final AssetLifecycleEventRepository lifecycleEventRepository;
    private final AssetService assetService;
    private final VehicleTrajectoryRepository vehicleTrajectoryRepository;
    private final SystemConfigRepository systemConfigRepository;

    /** 退役阈值：健康度下限（%）。 */
    private static final BigDecimal RETIRE_SOH_THRESHOLD = BigDecimal.valueOf(70);
    /** 退役阈值：最长服役年限（年）。 */
    private static final int SERVICE_LIFE_YEARS = 8;
    /** 退役阈值：无人机累计飞行时长上限（分钟）。 */
    private static final long DRONE_RETIRE_FLIGHT_MIN = 3000;
    /** 回收宽限期：退役后多少天进入回收。 */
    private static final long RECYCLE_GRACE_DAYS = 30;
    /** 车辆里程退役阈值（km）默认（system_config 缺省时回落）。 */
    private static final long DEFAULT_VEHICLE_RETIRE_ODOMETER_KM = 200000L;
    /** system_config 里程退役阈值键。 */
    private static final String VEHICLE_RETIRE_ODOMETER_KM_KEY = "VEHICLE_RETIRE_ODOMETER_KM";

    /** 每小时扫描一次（生产可调为每日凌晨）。 */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void scan() {
        retireElderlyAssets();
        recycleRetiredAssets();
        checkVehicleMileageRetirement();
    }

    /** 在网资产：SOH 过低或超期 → 退役。 */
    private void retireElderlyAssets() {
        List<Asset> active = assetRepository.findAll().stream()
                .filter(a -> a.getStatus() == AssetStatus.IN_USE || a.getStatus() == AssetStatus.SHARED)
                .toList();
        for (Asset a : active) {
            boolean lowSoh = readSoh(a.getId())
                    .map(soh -> soh.compareTo(RETIRE_SOH_THRESHOLD) < 0)
                    .orElse(false);
            boolean overAge = a.getCreatedAt() != null
                    && ChronoUnit.YEARS.between(a.getCreatedAt(), Instant.now()) >= SERVICE_LIFE_YEARS;
            boolean droneOverFlight = a.getAssetType() == AssetType.DRONE
                    && droneRepository.findByAssetId(a.getId())
                        .map(d -> d.getFlightMinutes() != null && d.getFlightMinutes() >= DRONE_RETIRE_FLIGHT_MIN)
                        .orElse(false);
            if (lowSoh || overAge || droneOverFlight) {
                String reason = droneOverFlight ? "无人机超飞行时长自动退役"
                        : (lowSoh ? "SOH 低于阈值自动退役" : "超服役年限自动退役");
                assetService.autoTransition(a.getId(), AssetStatus.RETIRED, reason);
            }
        }
    }

    /** 已退役资产：超过宽限期 → 回收。 */
    private void recycleRetiredAssets() {
        List<Asset> retired = assetRepository.findAll().stream()
                .filter(a -> a.getStatus() == AssetStatus.RETIRED)
                .toList();
        for (Asset a : retired) {
            lifecycleEventRepository.findByAssetIdOrderByOccurredAtDesc(a.getId()).stream()
                    .filter(e -> e.getStage() == AssetLifecycleStage.RETIRED)
                    .findFirst()
                    .ifPresent(e -> {
                        long days = ChronoUnit.DAYS.between(e.getOccurredAt(), Instant.now());
                        if (days >= RECYCLE_GRACE_DAYS) {
                            assetService.autoTransition(a.getId(), AssetStatus.RECYCLED,
                                    "退役满宽限期自动回收");
                        }
                    });
        }
    }

    /**
     * 车辆里程退役（仿无人机飞行时长退役）：VEHICLE/EV 资产累计里程
     * （vehicle_trajectory 最新点的 odometer_km）达到 system_config.VEHICLE_RETIRE_ODOMETER_KM
     * 阈值即自动退役。复用 {@link AssetService#autoTransition}（状态机校验 + 审计），仅对尚未退役的
     * 在网资产触发，autoTransition 内部再做状态机合法性校验，自然幂等（已退役不会重复流转）。
     */
    private void checkVehicleMileageRetirement() {
        long threshold = readVehicleRetireOdometerThreshold();
        List<Asset> vehicles = assetRepository.findAll().stream()
                .filter(a -> a.getAssetType() == AssetType.VEHICLE
                        || a.getAssetType() == AssetType.EV)
                .filter(a -> a.getStatus() == AssetStatus.IN_USE
                        || a.getStatus() == AssetStatus.SHARED)
                .toList();
        for (Asset a : vehicles) {
            Long odometer = vehicleTrajectoryRepository.findTopByAssetIdOrderByTDesc(a.getId())
                    .map(VehicleTrajectory::getOdometerKm)
                    .orElse(0L);
            if (isMileageRetire(odometer, threshold)) {
                assetService.autoTransition(a.getId(), AssetStatus.RETIRED,
                        "车辆累计里程超阈值自动退役");
            }
        }
    }

    /** 里程退役判定（抽成纯函数便于单测；odometerKm 为 null 视作 0，不触发）。 */
    boolean isMileageRetire(Long odometerKm, long threshold) {
        return odometerKm != null && odometerKm >= threshold;
    }

    /** 读取里程退役阈值；配置缺失/不可解析时回落默认 20 万公里。 */
    private long readVehicleRetireOdometerThreshold() {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse(VEHICLE_RETIRE_ODOMETER_KM_KEY)
                .map(c -> {
                    try {
                        return Long.parseLong(c.getConfigValue().trim());
                    } catch (NumberFormatException e) {
                        log.warn("VEHICLE_RETIRE_ODOMETER_KM 解析失败，回落默认 {}",
                                DEFAULT_VEHICLE_RETIRE_ODOMETER_KM);
                        return DEFAULT_VEHICLE_RETIRE_ODOMETER_KM;
                    }
                })
                .orElse(DEFAULT_VEHICLE_RETIRE_ODOMETER_KM);
    }

    /** SOH 数据源：遥测快照优先，缺失回退电池 SOH。 */
    private Optional<BigDecimal> readSoh(Long assetId) {
        return telemetryRepository.findByAssetId(assetId)
                .map(Telemetry::getSoh)
                .or(() -> batteryRepository.findByAssetId(assetId).map(b -> b.getSoh()));
    }
}
