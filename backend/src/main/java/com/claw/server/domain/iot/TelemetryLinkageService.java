package com.claw.server.domain.iot;

import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.DroneSafetyCause;
import com.claw.server.common.enums.LinkageDirection;
import com.claw.server.common.enums.LinkageStatus;
import com.claw.server.common.enums.LinkageTriggerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 遥测联动编排（设备数据四向闭环）。
 *
 * <p>在 {@link IoTService#reportTelemetry} 落库遥测后调用，对一次遥测做四向评估：
 * <ul>
 *   <li>ASSET_UPDATE — 每次上报都刷新资产的运营态快照（telemetry_latest 已由上层落库），落审计；</li>
 *   <li>REVENUE — 用量到达即触发收益分账重算意图（payload 携带用量快照），由资金域后续消费；</li>
 *   <li>RISK — 无人机低电量等触发 {@link RiskTriggerEvent}，风控域监听器锁机/告警；</li>
 *   <li>LIFECYCLE — SOH 低于阈值触发 {@link LifecycleTriggerEvent}，资产域监听器推进退役。</li>
 * </ul>
 *
 * <p>跨域协作走 Spring 领域事件（@TransactionalEventListener AFTER_COMMIT），避免在 iot 域直接依赖
 * 资产/风控/资金域（保持模块化单体边界，便于后续拆微服务）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetryLinkageService {

    /** 低电量阈值（%），低于即触发无人机锁机风控。 */
    private static final BigDecimal LOW_SOC_THRESHOLD = BigDecimal.valueOf(20);
    /** 退役健康度阈值（%），与 AssetLifecycleScheduler 一致。 */
    private static final BigDecimal RETIRE_SOH_THRESHOLD = BigDecimal.valueOf(70);

    private final ApplicationEventPublisher eventPublisher;
    private final DeviceLinkageEventRepository linkageEventRepository;

    /** 评估一次遥测并驱动四向联动。 */
    public void evaluate(TelemetryLatest latest, Device device) {
        Long assetId = latest.getAssetId();
        Long deviceId = latest.getDeviceId();

        // 1) 资产档案更新：运营态快照（telemetry_latest 已由上层落库），每次上报都驱动
        record(assetId, deviceId, LinkageDirection.ASSET_UPDATE, LinkageTriggerType.OPERATIONAL_SYNC,
                null, LinkageStatus.DONE);

        // 2) 收益分账：用量快照驱动重算意图
        String usageSnapshot = buildUsageSnapshot(latest);
        record(assetId, deviceId, LinkageDirection.REVENUE, LinkageTriggerType.USAGE,
                usageSnapshot, LinkageStatus.DONE);
        eventPublisher.publishEvent(new RevenueTriggerEvent(assetId, usageSnapshot));

        // 3) 风控告警：无人机低电量 → 锁机事件；其余资产故障码记入风控审计
        boolean drone = isDrone(device.getDeviceType());
        if (drone && latest.getSoc() != null && latest.getSoc().compareTo(LOW_SOC_THRESHOLD) < 0) {
            eventPublisher.publishEvent(new RiskTriggerEvent(assetId, AssetType.DRONE,
                    DroneSafetyCause.LOW_BATTERY,
                    "遥测电量 " + latest.getSoc() + "% 低于阈值 " + LOW_SOC_THRESHOLD + "%"));
            record(assetId, deviceId, LinkageDirection.RISK, LinkageTriggerType.LOW_SOC,
                    "LOW_BATTERY", LinkageStatus.DONE);
        } else if (hasFaults(latest.getFaults())) {
            record(assetId, deviceId, LinkageDirection.RISK, LinkageTriggerType.FAULT,
                    latest.getFaults(), LinkageStatus.DONE);
        }

        // 4) 全生命周期：SOH 过低 → 退役
        if (latest.getSoh() != null && latest.getSoh().compareTo(RETIRE_SOH_THRESHOLD) < 0) {
            eventPublisher.publishEvent(new LifecycleTriggerEvent(assetId, AssetStatus.RETIRED,
                    "遥测 SOH " + latest.getSoh() + "% 低于阈值 " + RETIRE_SOH_THRESHOLD + "%"));
            record(assetId, deviceId, LinkageDirection.LIFECYCLE, LinkageTriggerType.SOH_LOW,
                    "SOH=" + latest.getSoh(), LinkageStatus.DONE);
        }
    }

    private void record(Long assetId, Long deviceId, LinkageDirection direction,
                        LinkageTriggerType trigger, String payload, LinkageStatus status) {
        linkageEventRepository.save(DeviceLinkageEvent.builder()
                .assetId(assetId)
                .deviceId(deviceId)
                .direction(direction)
                .triggerType(trigger)
                .payload(payload)
                .status(status)
                .triggeredAt(Instant.now())
                .build());
    }

    private boolean isDrone(String deviceType) {
        return deviceType != null && deviceType.startsWith("DRONE");
    }

    private boolean hasFaults(String faults) {
        if (faults == null || faults.isBlank()) {
            return false;
        }
        String f = faults.trim();
        return !"[]".equals(f) && !"null".equalsIgnoreCase(f);
    }

    private String buildUsageSnapshot(TelemetryLatest l) {
        return "{\"soc\":" + (l.getSoc() != null ? l.getSoc() : "null")
                + ",\"speed\":" + (l.getSpeed() != null ? l.getSpeed() : "null")
                + ",\"temp\":" + (l.getTemp() != null ? l.getTemp() : "null") + "}";
    }
}
