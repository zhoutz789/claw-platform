package com.claw.server.domain.airspace;

import com.claw.server.common.enums.PilotBehaviorType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 飞手行为事件仓储（对应 claw.pilot_behavior_event）。
 */
public interface PilotBehaviorEventRepository extends JpaRepository<PilotBehaviorEvent, Long> {

    /** 某飞手的行为事件（按发生时间倒序）。 */
    List<PilotBehaviorEvent> findByPilotUserIdOrderByOccurredAtDesc(Long pilotUserId);

    /** 某资产上发生的行为事件（按发生时间倒序）。 */
    List<PilotBehaviorEvent> findByAssetIdOrderByOccurredAtDesc(Long assetId);

    /**
     * 某飞手某类行为的事件计数（风控/晋级依据）。
     *
     * <p>注意：{@code eventType} 是 {@code @Enumerated(EnumType.STRING)} 字段，派生查询参数
     * 必须传枚举类型，传 {@code String} 会在运行期类型不匹配。
     */
    long countByPilotUserIdAndEventType(Long pilotUserId, PilotBehaviorType eventType);
}
