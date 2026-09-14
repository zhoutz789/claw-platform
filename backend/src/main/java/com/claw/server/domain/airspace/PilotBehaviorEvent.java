package com.claw.server.domain.airspace;

import com.claw.server.common.enums.PilotBehaviorType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 飞手行为事件（对应 claw.pilot_behavior_event，V141 新增）。
 *
 * <p>由遥测链派生（越界 / 超载 / 失联 / 超时未作业 / 危险操作），本表只做
 * 「记录 + 评级」——<b>不直接处罚</b>，是否处罚由运营侧按 {@link PilotPenalty} 决策，
 * 这样行为事实与处罚决策各自可审计、可申诉。
 */
@Entity
@Table(name = "pilot_behavior_event", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PilotBehaviorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 飞手平台用户 id（冗余存 user_id，便于按人聚合，免 join 档案表）。 */
    @Column(name = "pilot_user_id", nullable = false)
    private Long pilotUserId;

    /** 发生事件的无人机 asset_id（可为空：纯人为违规无资产）。 */
    @Column(name = "asset_id")
    private Long assetId;

    /** 行为类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private PilotBehaviorType eventType;

    /** 严重度：LOW / MEDIUM / HIGH / CRITICAL。 */
    @Column(name = "severity", nullable = false, length = 16)
    @Builder.Default
    private String severity = "LOW";

    /** 事件明细（JSONB：遥测点位 / 载荷 / 失联时长等，结构随类型不同）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    private String detailJson;

    /** 来源引用（遥测记录 id / 安全事件 id / 任务 id，便于回溯）。 */
    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    /** 事件发生时刻。 */
    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private Instant occurredAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
