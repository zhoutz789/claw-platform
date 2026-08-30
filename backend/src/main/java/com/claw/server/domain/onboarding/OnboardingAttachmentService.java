package com.claw.server.domain.onboarding;

import com.claw.server.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 入驻资料附件服务（增量 C · O13 / B2）。
 *
 * <p>文件本身复用既有 {@code POST /api/v1/admin/upload} 上传，本服务只登记 URL 与元信息。
 * 审核结论落到<b>具体材料项</b>（{@code reviewStatus} + {@code reviewRemark}，
 * 如「营业执照照片模糊」），支撑「驳回到材料项、原表单改完重提，不回退空白」（B2）。
 *
 * <p>{@code SIGNBOARD} 的首图（sort_no 最小）在激活后回填为 {@code stations.signboard_url}
 * 作为对外展示图标（O14）；开关 {@code ONBOARDING_SIGNBOARD_REVIEW_REQUIRED} 为 true 时
 * 只回填审核通过的（Q10）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingAttachmentService {

    /** 单张附件大小上限：10MB。 */
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    /** MIME 白名单：JPG / PNG / PDF。 */
    private static final List<String> ALLOWED_MIME = List.of(
            "image/jpeg", "image/jpg", "image/png", "application/pdf");

    private final OnboardingAttachmentRepository attachmentRepository;
    private final OnboardingApplicationRepository applicationRepository;

    /** 取某申请单的全部附件（按材料码 + 排序）。 */
    @Transactional(readOnly = true)
    public List<OnboardingAttachment> listByApplication(Long applicationId) {
        return attachmentRepository.findByApplicationIdOrderByAttachTypeAscSortNoAsc(applicationId);
    }

    /** 取某申请单某材料码的附件。 */
    @Transactional(readOnly = true)
    public List<OnboardingAttachment> listByType(Long applicationId, String attachType) {
        return attachmentRepository.findByApplicationIdAndAttachTypeOrderBySortNoAsc(applicationId, attachType);
    }

    /**
     * 登记一个附件（URL 由前端上传后回传）。
     *
     * @throws BizException 40002 onboarding.attachment.mime.not.allowed / 40003 ...too.large
     */
    @Transactional
    public OnboardingAttachment add(Long applicationId, String attachType, String fileUrl, String fileName,
                                    Long fileSize, String mimeType, String remark, Long uploadedBy) {
        applicationRepository.findById(applicationId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.application.not.found"));
        if (fileUrl == null || fileUrl.isBlank()) {
            throw BizException.of(10001, "onboarding.attachment.url.required");
        }
        if (mimeType != null && !mimeType.isBlank() && !ALLOWED_MIME.contains(mimeType.toLowerCase())) {
            throw BizException.of(40002, "onboarding.attachment.mime.not.allowed", mimeType);
        }
        if (fileSize != null && fileSize > MAX_FILE_SIZE) {
            throw BizException.of(40003, "onboarding.attachment.too.large");
        }
        int nextSort = attachmentRepository
                .findByApplicationIdAndAttachTypeOrderBySortNoAsc(applicationId, attachType)
                .stream().mapToInt(a -> a.getSortNo() == null ? 0 : a.getSortNo()).max().orElse(-1) + 1;
        OnboardingAttachment att = OnboardingAttachment.builder()
                .applicationId(applicationId)
                .attachType(attachType)
                .fileUrl(fileUrl)
                .fileName(fileName)
                .fileSize(fileSize)
                .mimeType(mimeType)
                .remark(remark)
                .sortNo(nextSort)
                .uploadedBy(uploadedBy)
                .uploadedAt(Instant.now())
                .reviewStatus("PENDING")
                .build();
        return attachmentRepository.save(att);
    }

    /** 批量登记（多图一次提交）。 */
    @Transactional
    public List<OnboardingAttachment> addAll(Long applicationId, String attachType, List<String> fileUrls,
                                             String remark, Long uploadedBy) {
        return fileUrls.stream()
                .map(url -> add(applicationId, attachType, url, null, null, null, remark, uploadedBy))
                .toList();
    }

    /** 整组替换某材料码的附件（申请人在原表单改完重提时用）。 */
    @Transactional
    public List<OnboardingAttachment> replaceAll(Long applicationId, String attachType, List<String> fileUrls,
                                                 String remark, Long uploadedBy) {
        attachmentRepository.findByApplicationIdAndAttachTypeOrderBySortNoAsc(applicationId, attachType)
                .forEach(attachmentRepository::delete);
        return addAll(applicationId, attachType, fileUrls, remark, uploadedBy);
    }

    @Transactional
    public void delete(Long attachmentId) {
        OnboardingAttachment att = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.attachment.not.found"));
        attachmentRepository.delete(att);
    }

    /**
     * 逐材料项审核（PASSED / REJECTED），驳回须填原因。
     *
     * @param attachmentId 附件 id
     * @param reviewStatus PASSED / REJECTED / PENDING
     * @param reviewRemark 如「营业执照照片模糊」
     */
    @Transactional
    public OnboardingAttachment reviewItem(Long attachmentId, String reviewStatus, String reviewRemark) {
        OnboardingAttachment att = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.attachment.not.found"));
        if (!"PASSED".equals(reviewStatus) && !"REJECTED".equals(reviewStatus) && !"PENDING".equals(reviewStatus)) {
            throw BizException.of(10001, "onboarding.attachment.review.status.invalid", String.valueOf(reviewStatus));
        }
        if ("REJECTED".equals(reviewStatus) && (reviewRemark == null || reviewRemark.isBlank())) {
            throw BizException.of(10001, "onboarding.attachment.reject.reason.required");
        }
        att.setReviewStatus(reviewStatus);
        att.setReviewRemark(reviewRemark);
        return attachmentRepository.save(att);
    }

    /**
     * 取门头照片中「可对外展示」的首图 URL。
     *
     * @param applicationId 申请单 id
     * @param reviewRequired 是否要求审核通过（对应 ONBOARDING_SIGNBOARD_REVIEW_REQUIRED）
     * @return 首图 URL；无符合条件时返回 empty
     */
    @Transactional(readOnly = true)
    public Optional<String> signboardUrl(Long applicationId, boolean reviewRequired) {
        List<OnboardingAttachment> boards =
                attachmentRepository.findByApplicationIdAndAttachTypeOrderBySortNoAsc(applicationId, "SIGNBOARD");
        if (reviewRequired) {
            return attachmentRepository
                    .findTopByApplicationIdAndAttachTypeAndReviewStatusOrderBySortNoAsc(
                            applicationId, "SIGNBOARD", "PASSED")
                    .map(OnboardingAttachment::getFileUrl);
        }
        return boards.stream().findFirst().map(OnboardingAttachment::getFileUrl);
    }
}
