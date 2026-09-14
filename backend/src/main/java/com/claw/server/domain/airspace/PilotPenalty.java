package com.claw.server.domain.airspace;

import com.claw.server.common.enums.PilotPenaltyType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 飞手违规处罚历史（对应 claw.pilot_penalty，V141 新增）。
 *
 * <p>只追加不覆盖：一次违规一条记录，{@code pilotId} 指向 {@link PilotProfile#getId()}，
 * 处罚历史随档案聚合（档案可换状态，历史不改写）。处罚对档案状态的副作用由
 * {@code PilotOpsService#penalize} 显式施加并与之同事务提交。
 */
@Entity
@Table(name = "pilot_penalty", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PilotPenalty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 飞手档案 id（FK → pilot_profile.id）。 */
    @Column(name = "pilot_id", nullable = false)
    private Long pilotId;

    /** 违规原因代码（引用 {@code PilotBehaviorType} 名或运营自定义码）。 */
    @Column(name = "cause", nullable = false, length = 64)
    private String cause;

    /** 严重度：LOW / MEDIUM / HIGH / CRITICAL。 */
    @Column(name = "severity", nullable = false, length = 16)
    @Builder.Default
    private String severity = "LOW";

    /** 扣减信用分（WARN 少量 / FINE 较多；SUSPEND/REVOKE 亦记分以便统计）。 */
    @Column(name = "points", nullable = false)
    @Builder.Default
    private Integer points = 0;

    /** 处罚类型（决定对档案状态的副作用）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", nullable = false, length = 16)
    private PilotPenaltyType penaltyType;

    /** 业务引用（任务 id / 作业架次 / 事件 id）。 */
    @Column(name = "biz_ref", length = 64)
    private String bizRef;

    /** 处罚单状态：ACTIVE / REVOKED / EXPIRED。 */
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    /** 生效截止（SUSPEND 到期自动恢复用；为空表示长期/永久）。 */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** 决策人用户 id。 */
    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    @Builder.Default
    private Instant decidedAt = Instant.now();

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
