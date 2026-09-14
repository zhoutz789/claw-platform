package com.claw.server.domain.airspace;

import com.claw.server.common.enums.PilotStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 飞手档案（对应 claw.pilot_profile，V141 新增）。
 *
 * <p><b>为什么需要本表</b>：{@code pilot_licenses}（V29）只有执照信息，<b>没有 user_id</b>，
 * 无法把「平台用户」与「持证飞手」绑定（技术方案决策 D5 的关键缺口）。本表以
 * {@code userId} 唯一绑定平台用户，并承载审核状态、信用分与处罚联动所需的状态位。
 *
 * <p>{@code licenseNo} 仅作弱引用（可为空）：未持证者也可先建档，审核通过时再补录；
 * 不加外键 —— 执照登记与飞手建档生命周期不同，且历史执照续期会换号。
 */
@Entity
@Table(name = "pilot_profile", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PilotProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 平台用户 id（唯一；飞手 ↔ 用户绑定）。 */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 执照编号（弱引用 pilot_licenses.license_no；可为空）。 */
    @Column(name = "license_no", length = 64)
    private String licenseNo;

    /** KYC 等级：BASIC / STANDARD / ENHANCED。 */
    @Column(name = "kyc_level", nullable = false, length = 16)
    @Builder.Default
    private String kycLevel = "BASIC";

    /** 档案审核状态（PENDING → ACTIVE；可被 SUSPENDED / BANNED）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private PilotStatus status = PilotStatus.PENDING;

    /** 飞手等级（1 起，随作业量与合规记录晋级）。 */
    @Column(name = "level", nullable = false)
    @Builder.Default
    private Integer level = 1;

    /** 信用分（0-100 起，违规扣减）。 */
    @Column(name = "credit_score", nullable = false)
    @Builder.Default
    private Integer creditScore = 100;

    /** 审核人用户 id。 */
    @Column(name = "approved_by")
    private Long approvedBy;

    /** 审核通过时间。 */
    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 1L;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
