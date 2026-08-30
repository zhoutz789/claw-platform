package com.claw.server.domain.onboarding;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 组织状态变更留痕（对应 claw.onboarding_org_status_logs，V61，本设计新增表）。
 *
 * <p>禁用 / 启用要求「填原因 + 二次确认 + 留痕」（O36）。禁用是<b>组织治理动作</b>，
 * 语义上不属于申请单日志；{@code audit_logs} 是通用非结构化审计，无法满足
 * 「按组织查禁用历史」，故单独建表。
 */
@Entity
@Table(name = "onboarding_org_status_logs", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingOrgStatusLog {

    /** 治理动作。 */
    public enum Action {
        ACTIVATE, DISABLE, ENABLE, REJECT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "principal_type", nullable = false, length = 20)
    private String principalType;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @Column(name = "from_status", length = 24)
    private String fromStatus;

    /** ACTIVE / DISABLED / REJECTED。 */
    @Column(name = "to_status", nullable = false, length = 24)
    private String toStatus;

    @Column(nullable = false, length = 24)
    private String action;

    /** 禁用原因（禁用时必填）。 */
    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
