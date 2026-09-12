package com.claw.server.domain.autonomy;

import com.claw.server.common.enums.SafetyState;
import com.claw.server.domain.asset.VehicleCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 安全事件服务（AU5）：记录事件，严重或关键原因时联动锁机（SafetyState.LOCKED）。
 */
@Service
@RequiredArgsConstructor
public class AutonomySafetyService {

    private static final Set<String> LOCK_CAUSES =
            Set.of("GEOFENCE", "LOST_LINK", "LOW_BATTERY", "OBSTACLE", "E_STOP");

    private final AutonomySafetyEventRepository eventRepository;
    private final AutonomyModuleService autonomyModuleService;
    private final VehicleCommandService vehicleCommandService;

    /**
     * 记录安全事件；CRITICAL 或关键原因时锁机。
     */
    @Transactional
    public AutonomySafetyEvent recordEvent(Long assetId, String cause, String severity, String detailJson) {
        AutonomySafetyEvent event = AutonomySafetyEvent.builder()
                .assetId(assetId)
                .cause(cause)
                .severity(severity)
                .detailJson(detailJson)
                .build();
        AutonomySafetyEvent saved = eventRepository.save(event);
        if ("CRITICAL".equals(severity) || LOCK_CAUSES.contains(cause)) {
            autonomyModuleService.setSafetyState(assetId, SafetyState.LOCKED);
        }
        return saved;
    }

    /**
     * AU5 触发安全事件（CRITICAL 或关键原因时联动锁机）。语义同 {@link #recordEvent}。
     */
    @Transactional
    public AutonomySafetyEvent triggerSafetyEvent(Long assetId, String cause, String severity, String detailJson) {
        return recordEvent(assetId, cause, severity, detailJson);
    }

    /**
     * AU5 安全锁机：将模块置 LOCKED（禁行）并下发车辆锁车指令。
     */
    @Transactional
    public void lockForSafety(Long assetId) {
        autonomyModuleService.setSafetyState(assetId, SafetyState.LOCKED);
        vehicleCommandService.lock(assetId);
    }

    public List<AutonomySafetyEvent> getByAsset(Long assetId) {
        return eventRepository.findAll().stream()
                .filter(e -> assetId.equals(e.getAssetId()))
                .toList();
    }
}
