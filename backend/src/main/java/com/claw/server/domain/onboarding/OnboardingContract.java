package com.claw.server.domain.onboarding;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 入驻说明与合同版本（对应 claw.onboarding_contracts，V59）。
 *
 * <p>发布即冻结：修改内容必须发新版本（版本号 {@code v{major}.{minor}}），
 * 历史版本只读留存、可对比、可回滚。同一 {@code applicant_type + lang} 同时最多一个 PUBLISHED 版本
 * （由 {@code uq_onb_contract_published} 部分唯一索引保证）。
 *
 * <p>争议以申请单签署时点的版本为准，故申请单冗余快照 {@code contract_version}（B5）。
 */
@Entity
@Table(name = "onboarding_contracts", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** STATION / MANUFACTURER / MERCHANT。 */
    @Column(name = "applicant_type", nullable = false, length = 20)
    private String applicantType;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false, length = 160)
    private String title;

    /** 富文本合作要点。 */
    @Column(name = "content_html", nullable = false, columnDefinition = "text")
    private String contentHtml;

    /** 公司签章合同扫描件（复用既有 POST /api/v1/admin/upload 上传）。 */
    @Column(name = "contract_file_url", length = 500)
    private String contractFileUrl;

    /** zh / km / en。 */
    @Column(nullable = false, length = 8)
    @Builder.Default
    private String lang = "zh";

    /** DRAFT / PUBLISHED / ARCHIVED。 */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;

    /** 内容指纹，版本对比用。 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** 是否生效中（仅 PUBLISHED 对外可见）。 */
    public boolean isPublished() {
        return "PUBLISHED".equals(status);
    }
}
