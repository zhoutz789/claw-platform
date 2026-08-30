package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.onboarding.ApplicationDetail;
import com.claw.server.domain.onboarding.OnboardingApplication;
import com.claw.server.domain.onboarding.OnboardingApplicationService;
import com.claw.server.domain.onboarding.OnboardingAttachmentService;
import com.claw.server.domain.onboarding.OnboardingContract;
import com.claw.server.domain.onboarding.OnboardingContractService;
import com.claw.server.domain.onboarding.OnboardingDepositService;
import com.claw.server.domain.onboarding.OnboardingDepositTier;
import com.claw.server.domain.onboarding.OnboardingDepositTierRepository;
import com.claw.server.domain.onboarding.OnboardingFormReq;
import com.claw.server.domain.onboarding.OnboardingMaterialRequirement;
import com.claw.server.domain.onboarding.OnboardingMaterialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 入驻申请「申请方」接口（增量 C · 页面 1–3）。
 *
 * <p>面向游客与申请人：查看入驻说明与合同、提交 / 保存申请、查看我的入驻进度、上传缴款凭证。
 * 权限码 {@code onboarding:apply:self}（已挂进 CUSTOMER / STATION / MANUFACTURER / MERCHANT 模板）。
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingContractService contractService;
    private final OnboardingApplicationService applicationService;
    private final OnboardingMaterialService materialService;
    private final OnboardingAttachmentService attachmentService;
    private final OnboardingDepositService depositService;
    private final OnboardingDepositTierRepository tierRepository;

    /* ---------------- 入驻说明与合同（公开） ---------------- */

    /** 当前生效的入驻说明 + 合同扫描件（含版本号，O1/O2）。 */
    @GetMapping("/content/{applicantType}")
    public ApiResult<OnboardingContract> content(@PathVariable String applicantType,
                                                 @RequestParam(defaultValue = "zh") String lang) {
        return ApiResult.ok(contractService.requirePublished(applicantType, lang));
    }

    /** 入驻说明历史版本列表（可对比 / 回滚）。 */
    @GetMapping("/content/{applicantType}/versions")
    public ApiResult<List<OnboardingContract>> contentVersions(@PathVariable String applicantType,
                                                               @RequestParam(defaultValue = "zh") String lang) {
        return ApiResult.ok(contractService.listVersions(applicantType, lang));
    }

    /** 该类主体的材料清单（前端据此动态渲染表单，O7/B1）。 */
    @GetMapping("/materials/{applicantType}")
    public ApiResult<List<OnboardingMaterialRequirement>> materials(@PathVariable String applicantType) {
        return ApiResult.ok(materialService.listEnabled(applicantType));
    }

    /** 该类主体的保证金档位（含授信额度，引导升档）。 */
    @GetMapping("/deposit-tiers/{applicantType}")
    public ApiResult<List<OnboardingDepositTier>> depositTiers(@PathVariable String applicantType) {
        return ApiResult.ok(tierRepository.findByApplicantTypeAndEnabledTrueOrderBySortNoAsc(applicantType));
    }

    /* ---------------- 我的入驻 ---------------- */

    /** 我的入驻进度（全部主体类型）。 */
    @GetMapping("/applications/my")
    public ApiResult<List<OnboardingApplication>> myApplications() {
        return ApiResult.ok(applicationService.listMine(currentUserId()));
    }

    /** 我的某主体类型申请。 */
    @GetMapping("/applications/my/{applicantType}")
    public ApiResult<List<OnboardingApplication>> myApplicationsByType(@PathVariable String applicantType) {
        PrincipalType.of(applicantType);
        return ApiResult.ok(applicationService.listMineByType(currentUserId(), applicantType));
    }

    /** 取或建草稿（「继续填写」入口，O9）。 */
    @GetMapping("/applications/draft")
    public ApiResult<OnboardingApplication> getDraft(@RequestParam String applicantType,
                                                     @RequestParam(defaultValue = "zh") String lang) {
        return ApiResult.ok(applicationService.getOrCreateDraft(currentUserId(), applicantType, lang));
    }

    /** 保存草稿（分步保存，不做必填校验，O9）。 */
    @PostMapping("/applications/draft")
    public ApiResult<OnboardingApplication> saveDraft(@RequestBody @Valid OnboardingFormReq req) {
        return ApiResult.ok(applicationService.saveDraft(currentUserId(), req));
    }

    /** 提交申请（全量必填校验 + 合同版本快照 + 勾选同意，O3）。 */
    @PostMapping("/applications/submit")
    public ApiResult<OnboardingApplication> submit(@RequestBody @Valid OnboardingFormReq req) {
        return ApiResult.ok(applicationService.submit(currentUserId(), req));
    }

    /** 申请详情（含驳回原因与不合格材料项，便于改完重提，B2）。 */
    @GetMapping("/applications/{id}")
    public ApiResult<ApplicationDetail> detail(@PathVariable Long id) {
        return ApiResult.ok(applicationService.detail(id));
    }

    /** 撤回申请。 */
    @PostMapping("/applications/{id}/cancel")
    public ApiResult<OnboardingApplication> cancel(@PathVariable Long id) {
        return ApiResult.ok(applicationService.cancel(id, currentUserId()));
    }

    /** 重新激活已超时（EXPIRED）的申请。 */
    @PostMapping("/applications/{id}/reactivate")
    public ApiResult<OnboardingApplication> reactivate(@PathVariable Long id) {
        return ApiResult.ok(applicationService.reactivate(id, currentUserId()));
    }

    /* ---------------- 材料附件 ---------------- */

    /** 某申请单的全部材料。 */
    @GetMapping("/applications/{id}/attachments")
    public ApiResult<List<?>> attachments(@PathVariable Long id) {
        return ApiResult.ok(attachmentService.listByApplication(id));
    }

    /** 替换某材料项的附件（改完重提，B2）。 */
    @PostMapping("/applications/{id}/attachments")
    public ApiResult<List<?>> replaceAttachments(@PathVariable Long id, @RequestBody AttachmentReq req) {
        return ApiResult.ok(attachmentService.replaceAll(id, req.attachType(), req.fileUrls(),
                req.remark(), currentUserId()));
    }

    /** 删除某个附件。 */
    @DeleteMapping("/attachments/{attachmentId}")
    public ApiResult<Void> deleteAttachment(@PathVariable Long attachmentId) {
        attachmentService.delete(attachmentId);
        return ApiResult.ok();
    }

    /* ---------------- 保证金缴纳 ---------------- */

    /** 上传缴款凭证（首期线下转账，Q4/O19）。 */
    @PostMapping("/applications/{id}/voucher")
    public ApiResult<?> submitVoucher(@PathVariable Long id, @RequestBody VoucherReq req) {
        return ApiResult.ok(depositService.submitVoucher(id, req.amount(), req.voucherUrl(),
                req.payerName(), req.payerAccount(), req.payMethod(), currentUserId()));
    }

    /** 我的缴款记录。 */
    @GetMapping("/deposits/my")
    public ApiResult<List<?>> myDeposits() {
        return ApiResult.ok(depositService.listMine(currentUserId()));
    }

    /** 状态标签映射（供前端统一渲染中文标签，周老板要求）。 */
    @GetMapping("/status-labels")
    public ApiResult<Map<String, String>> statusLabels() {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        for (com.claw.server.common.enums.OnboardingApplicationStatus s
                : com.claw.server.common.enums.OnboardingApplicationStatus.values()) {
            labels.put(s.name(), s.label());
        }
        return ApiResult.ok(labels);
    }

    private static Long currentUserId() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw new com.claw.server.common.api.BizException(40301, "login.required");
        }
        return uid;
    }

    /** 附件替换请求。 */
    public record AttachmentReq(String attachType, List<String> fileUrls, String remark) {
    }

    /** 缴款凭证请求。 */
    public record VoucherReq(BigDecimal amount, String voucherUrl, String payerName,
                             String payerAccount, String payMethod) {
    }
}
