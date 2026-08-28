package com.claw.server.domain.airspace;

import jakarta.persistence.*;
import com.claw.server.common.enums.FlightPlanStatus;
import lombok.*;

import java.time.Instant;

/**
 * 飞行计划（对应 claw.flight_plans）。
 * 无人机每次作业前须提交计划：指定空域分区 + 飞手资质，平台校验合规后放行。
 * 越界/失联/低电量由监控（风控域）触发锁机，类比车辆「断缴锁车」。
 */
@Entity
@Table(name = "flight_plans", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;   // 无人机资产 id

    @Column(nullable = false)
    private Long zoneId;    // 空域分区 id

    @Column(nullable = false)
    private Long pilotId;   // 飞手用户 id

    @Column(nullable = false)
    private Instant plannedAt;

    /** DRAFT 草稿 / APPROVED 已批准 / ACTIVE 执行中 / COMPLETED 已完成 / VIOLATED 违规。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FlightPlanStatus status = FlightPlanStatus.DRAFT;

    private String routeNote;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
