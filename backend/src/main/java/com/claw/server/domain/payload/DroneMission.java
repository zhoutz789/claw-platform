package com.claw.server.domain.payload;

import jakarta.persistence.*;
import com.claw.server.common.enums.DroneMissionType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 无人机作业计量（对应 claw.drone_missions）。
 * 按载荷类型记录作业量：SPRAY 喷洒公顷数 / CARGO 配送趟数 / INSPECTION 巡检里程 /
 * RESCUE 救援时长，作为任务发布与分账的计量依据（复用 TaskPublish + 分账逻辑）。
 */
@Entity
@Table(name = "drone_missions", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DroneMission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;   // 无人机资产 id

    /** SPRAY 植保 / CARGO 物流 / INSPECTION 巡检 / RESCUE 救援。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DroneMissionType missionType;

    private String payloadDesc;

    /** 喷洒面积（公顷，SPRAY 用）。 */
    private BigDecimal areaHa;

    /** 配送趟数（CARGO 用）。 */
    private Integer trips;

    /** 本次飞行时长（分钟）。 */
    private Integer flightMinutes;

    @Column(nullable = false)
    private Long pilotId;

    @Column(nullable = false)
    private Instant executedAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
