package com.claw.server.domain.onboarding;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 入驻申请审批 / 操作留痕时间轴（对应 claw.onboarding_application_logs，V59）。
 *
 * <p>记录「谁、何时、动作、意见、前后状态」，支撑详情页一屏决策与事后追溯（O17）。
 * {@code payload_json} 存变更明细（哪个字段 / 哪个材料项被驳回）。
 *
 * <p>⚠️ {@code payload_json} 是 JSONB，<b>必须</b>标注 {@code @JdbcTypeCode(SqlTypes.JSON)}，
 * 否则真库报「column payload_json is of type jsonb but expression is of type character varying」
 * （null 也按 varchar 发参）。
 */
@Entity
@Table(name = "onboarding_application_logs", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingApplicationLog {

    /** 动作类型。 */
    public enum Action {
        CREATE, SAVE_DRAFT, SUBMIT, APPROVE, REJECT, RETURN,
        PAY_SUBMIT, PAY_CONFIRM, PAY_REJECT, ACTIVATE, CANCEL, EXPIRE
    }

    /** 操作人类型。 */
    public enum OperatorType {
        PLATFORM, APPLICANT, SYSTEM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "from_status", length = 24)
    private String fromStatus;

    @Column(name = "to_status", length = 24)
    private String toStatus;

    @Column(nullable = false, length = 24)
    private String action;

    /** SYSTEM 动作为 NULL。 */
    @Column(name = "operator_id")
    private Long operatorId;

    /** PLATFORM / APPLICANT / SYSTEM。 */
    @Column(name = "operator_type", nullable = false, length = 16)
    @Builder.Default
    private String operatorType = OperatorType.PLATFORM.name();

    /** 审核意见。 */
    @Column(columnDefinition = "text")
    private String remark;

    /** 变更明细（哪个字段 / 材料被驳回）。JSONB。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
