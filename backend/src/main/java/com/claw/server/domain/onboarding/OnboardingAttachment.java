package com.claw.server.domain.onboarding;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 入驻资料附件（对应 claw.onboarding_attachments，V59）。
 *
 * <p>统一表，按 {@code attach_type}（材料码）分类。审核结论落到<b>具体材料项</b>
 * （{@code review_status} + {@code review_remark}，如「营业执照照片模糊」），
 * 支撑 B2「驳回精确到材料项、原地修改重提」。
 *
 * <p>{@code SIGNBOARD} 的首图（sort_no 最小）在激活后回填为 {@code stations.signboard_url}
 * 作为对外展示图标（O14），且需先经平台审核通过（Q10）。
 */
@Entity
@Table(name = "onboarding_attachments", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    /** 材料码（见 MaterialCode）。 */
    @Column(name = "attach_type", nullable = false, length = 32)
    private String attachType;

    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    @Column(name = "file_name", length = 255)
    private String fileName;

    /** 单张 ≤ 10MB（应用侧校验）。 */
    @Column(name = "file_size")
    private Long fileSize;

    /** JPG / PNG / PDF 白名单。 */
    @Column(name = "mime_type", length = 80)
    private String mimeType;

    /** 相关附件说明（用户原话第 16 项）。 */
    @Column(columnDefinition = "text")
    private String remark;

    /** 多图排序；SIGNBOARD 首图 = 对外展示图标。 */
    @Column(name = "sort_no", nullable = false)
    @Builder.Default
    private Integer sortNo = 0;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    @Builder.Default
    private Instant uploadedAt = Instant.now();

    /** PENDING / PASSED / REJECTED。 */
    @Column(name = "review_status", nullable = false, length = 16)
    @Builder.Default
    private String reviewStatus = "PENDING";

    @Column(name = "review_remark", length = 255)
    private String reviewRemark;

    /** 是否审核通过。 */
    public boolean isPassed() {
        return "PASSED".equals(reviewStatus);
    }
}
