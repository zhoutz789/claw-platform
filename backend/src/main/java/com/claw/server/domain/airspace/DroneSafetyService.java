package com.claw.server.domain.airspace;

import com.claw.server.common.enums.DroneSafetyCause;
import com.claw.server.common.enums.DroneSafetyEventStatus;
import com.claw.server.common.enums.DroneSafetyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 无人机飞行安全管控服务（类比车辆断缴锁车）。
 * 资产存在任一 OPEN 安全事件即判定 LOCKED，禁止起飞；解除后恢复正常。
 */
@Service
@RequiredArgsConstructor
public class DroneSafetyService {

    private final DroneSafetyEventRepository eventRepository;

    /** 当前安全态：存在 OPEN 事件即为 LOCKED。 */
    public DroneSafetyStatus currentStatus(Long assetId) {
        boolean locked = eventRepository.existsByAssetIdAndStatus(assetId, DroneSafetyEventStatus.OPEN);
        return locked ? DroneSafetyStatus.LOCKED : DroneSafetyStatus.NORMAL;
    }

    public List<DroneSafetyEvent> listEvents(Long assetId) {
        return eventRepository.findByAssetIdOrderByCreatedAtDesc(assetId);
    }

    /** 模拟触发锁机（越界/失联/低电量/人工）。 */
    @Transactional
    public DroneSafetyEvent simulate(Long assetId, DroneSafetyCause cause, String detail) {
        DroneSafetyEvent e = DroneSafetyEvent.builder()
                .assetId(assetId)
                .cause(cause)
                .status(DroneSafetyEventStatus.OPEN)
                .detail(detail)
                .createdAt(Instant.now())
                .build();
        return eventRepository.save(e);
    }

    /** 解除最近一条未解除的锁机事件。 */
    @Transactional
    public DroneSafetyEvent resolve(Long assetId) {
        return eventRepository.findByAssetIdAndStatus(assetId, DroneSafetyEventStatus.OPEN).stream()
                .findFirst()
                .map(e -> {
                    e.setStatus(DroneSafetyEventStatus.RESOLVED);
                    e.setResolvedAt(Instant.now());
                    return eventRepository.save(e);
                })
                .orElseThrow(() -> new IllegalArgumentException("无未解除的锁机事件"));
    }
}
