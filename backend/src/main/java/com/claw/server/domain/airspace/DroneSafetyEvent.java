package com.claw.server.domain.airspace;

import jakarta.persistence.*;
import com.claw.server.common.enums.DroneSafetyCause;
import com.claw.server.common.enums.DroneSafetyEventStatus;
import lombok.*;

import java.time.Instant;

/**
 * 无人机飞行安全事件（对应 claw.drone_safety_events）。
 * 任一条 OPEN 事件即判定资产 LOCKED（类比车辆断缴锁车）；resolve 后恢复正常。
 * 这是低空经济合规闭环的"安全闸"：不能只管飞不管安全。
 */
@Entity
@Table(name = "drone_safety_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DroneSafetyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /** 触发原因：越界 / 失联 / 低电量 / 人工。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DroneSafetyCause cause;

    /** OPEN 锁机中 / RESOLVED 已解除。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DroneSafetyEventStatus status = DroneSafetyEventStatus.OPEN;

    private String detail;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant resolvedAt;
}
