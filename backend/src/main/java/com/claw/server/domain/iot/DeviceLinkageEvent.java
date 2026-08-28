package com.claw.server.domain.iot;

import com.claw.server.common.enums.LinkageDirection;
import com.claw.server.common.enums.LinkageStatus;
import com.claw.server.common.enums.LinkageTriggerType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 设备联动事件（对应 claw.device_linkage_events，V31 新增）。
 * 遥测到达后四向联动（资产档案 / 收益 / 风控 / 全生命周期）的落地审计：
 * 每一方向命中即落一条记录，便于运营核查「设备数据 → 业务闭环」是否真正被驱动。
 */
@Entity
@Table(name = "device_linkage_events", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceLinkageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    private Long deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LinkageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LinkageTriggerType triggerType;

    /** 联动命中时的关键载荷（如用量快照、触发阈值）。JSON 文本。 */
    @Column(columnDefinition = "TEXT")
    private String payload;

    /** DONE 已执行 / SKIPPED 未达阈值 / ERROR 执行异常（预留）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private LinkageStatus status = LinkageStatus.DONE;

    @Column(nullable = false)
    @Builder.Default
    private Instant triggeredAt = Instant.now();

    @Builder.Default
    private Long tenantId = 1L;
}
