package com.claw.server.domain.compliance;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 任务/架次 ↔ 空域许可绑定（对应 claw.drone_mission_permit_bindings，V143 新增）。
 *
 * <p>把「任务/架次」与「许可」显式挂钩，形成可审计链路：哪一次作业用了哪张许可。
 * "是否强制绑定"为开关（V139 {@code permit_gate_mode}），本表只做留痕与查询，
 * 不承担强制语义（强制语义在 {@link PermitGate}）。
 */
@Entity
@Table(name = "drone_mission_permit_bindings", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DroneMissionPermitBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 任务大厅任务 id（可空：非任务来源的架次）。 */
    @Column(name = "task_id")
    private Long taskId;

    /** 作业计量记录 id（claw.drone_missions.id，可空）。 */
    @Column(name = "drone_mission_id")
    private Long droneMissionId;

    /** 绑定的许可 id（FK → drone_airspace_permits.id）。 */
    @Column(name = "permit_id", nullable = false)
    private Long permitId;

    @Column(name = "bound_at", nullable = false)
    @Builder.Default
    private Instant boundAt = Instant.now();

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
