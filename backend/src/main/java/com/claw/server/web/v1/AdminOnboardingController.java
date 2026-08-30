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
import com.claw.server.domain.onboarding.OnboardingDeposit;
import com.claw.server.domain.onboarding.OnboardingDepositService;
import com.claw.server.domain.onboarding.OnboardingDepositTier;
import com.claw.server.domain.onboarding.OnboardingMaterialRequirement;
import com.claw.server.domain.onboarding.OnboardingMaterialService;
import com.claw.server.domain.onboarding.OnboardingDepositTierRepository;
import com.claw.server.domain.onboarding.ActivationResult;
import com.claw.server.domain.onboarding.OnboardingActivationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 入驻管理后台接口（增量 C · 页面 4–8）。
 *
 * <p>面向平台管理员与财务：入驻申请列表与审核（通过 / 驳回到材料项 / 退回补正）、
 * 入驻说明与合同版本配置、保证金档位 CRUD、保证金到账确认（触发自动激活）。
 *
 * <p>权限码：{@code onboarding:review:manage} / {@code onboarding:content:manage} /
 * {@code onboarding:deposit:manage} / {@code onboarding:deposit:confirm}。
 */
@RestController
@RequestMapping("/api/v1/admin/onboarding")
@RequiredArgsConstructor
public class AdminOnboardingController {

    private final OnboardingApplicationService applicationService;
    private final OnboardingContractService contractService;
    private final OnboardingMaterialService materialService;
    private final OnboardingAttachmentService attachmentService;
    private final OnboardingDepositService depositService;
    private final OnboardingDepositTierRepository tierRepository;
    private final OnboardingActivationService activationService;

    /* ---------------- 申请列表与审核（O34 / O35 / O16） ---------------- */

    /** 入驻申请列表（按主体类型 / 状态筛选，行内审核按钮）。 */
    @GetMapping("/applications")
    public ApiResult<List<OnboardingApplication>> listApplications(@RequestParam(required = false) String applicantType,
                                                                   @RequestParam(required = false) String status) {
        return ApiResult.ok(applicationService.listForReview(applicantType, status));
    }

    /** 申请详情（一屏决策：材料 / 认证 / 定位 / 合同版本 / 时间轴）。 */
    @GetMapping("/applications/{id}")
    public ApiResult<ApplicationDetail> applicationDetail(@PathVariable Long id) {
        return ApiResult.ok(applicationService.detail(id));
    }

    /** 审核：APPROVE / REJECT / RETURN，支持逐材料项结论（B2）。 */
    @PostMapping("/applications/{id}/review")
    public ApiResult<OnboardingApplication> review(@PathVariable Long id, @RequestBody ReviewReq req) {
        return ApiResult.ok(applicationService.review(id, req.action(), req.reason(), req.items(),
                currentUserId()));
    }

    /** 置身份证认证结果（Q5：P0 人工审核，P1 接 CamDigiKey）。 */
    @PostMapping("/applications/{id}/kyc")
    public ApiResult<OnboardingApplication> setKyc(@PathVariable Long id, @RequestBody KycReq req) {
        return ApiResult.ok(applicationService.setKycStatus(id, req.kycStatus(), req.kycRecordId(),
                currentUserId()));
    }

    /** 缴款超时扫描（可挂定时任务，O21）。 */
    @PostMapping("/applications/expire-sweep")
    public ApiResult<Integer> expireSweep() {
        return ApiResult.ok(applicationService.expireOverdue());
    }

    /** 待重试激活列表（激活失败人工补偿入口，Q8）。 */
    @GetMapping("/activations/pending")
    public ApiResult<List<OnboardingApplication>> pendingActivations() {
        return ApiResult.ok(activationService.listPendingActivation());
    }

    /** 人工重试激活（幂等）。 */
    @PostMapping("/applications/{id}/retry-activation")
    public ApiResult<ActivationResult> retryActivation(@PathVariable Long id) {
        return ApiResult.ok(activationService.retry(id));
    }

    /* ---------------- 入驻说明与合同（O1 / O2） ---------------- */

    @GetMapping("/contracts")
    public ApiResult<List<OnboardingContract>> listContracts(@RequestParam String applicantType,
                                                             @RequestParam(defaultValue = "zh") String lang) {
        return ApiResult.ok(contractService.listVersions(applicantType, lang));
    }

    /** 保存说明草稿。 */
    @PostMapping("/contracts/draft")
    public ApiResult<OnboardingContract> saveContractDraft(@RequestBody ContractReq req) {
        return ApiResult.ok(contractService.saveDraft(req.id(), req.applicantType(), req.lang(),
                req.title(), req.contentHtml(), req.contractFileUrl()));
    }

    /** 发布新版本（自动生成版本号，O1）。 */
    @PostMapping("/contracts/publish")
    public ApiResult<OnboardingContract> publishContract(@RequestBody ContractReq req) {
        return ApiResult.ok(contractService.publishNewVersion(req.applicantType(), req.lang(), req.title(),
                req.contentHtml(), req.contractFileUrl(), Boolean.TRUE.equals(req.major()), currentUserId()));
    }

    /** 回滚到历史版本。 */
    @PostMapping("/contracts/{id}/rollback")
    public ApiResult<OnboardingContract> rollbackContract(@PathVariable Long id) {
        return ApiResult.ok(contractService.rollback(id, currentUserId()));
    }

    /* ---------------- 材料清单配置（O7） ---------------- */

    @GetMapping("/materials/{applicantType}")
    public ApiResult<List<OnboardingMaterialRequirement>> listMaterials(@PathVariable String applicantType) {
        return ApiResult.ok(materialService.listAll(applicantType));
    }

    @PostMapping("/materials")
    public ApiResult<OnboardingMaterialRequirement> upsertMaterial(@RequestBody MaterialReq req) {
        return ApiResult.ok(materialService.upsert(req.id(), req.applicantType(), req.materialCode(),
                req.materialName(), req.inputType(), req.required(), req.minCount(), req.maxCount(),
                req.hint(), req.sortNo()));
    }

    @PostMapping("/materials/{id}/enabled")
    public ApiResult<OnboardingMaterialRequirement> setMaterialEnabled(@PathVariable Long id,
                                                                       @RequestParam boolean enabled) {
        return ApiResult.ok(materialService.setEnabled(id, enabled));
    }

    @DeleteMapping("/materials/{id}")
    public ApiResult<Void> deleteMaterial(@PathVariable Long id) {
        materialService.delete(id);
        return ApiResult.ok();
    }

    /* ---------------- 保证金档位（O24 / Q2b） ---------------- */

    @GetMapping("/deposit-tiers")
    public ApiResult<List<OnboardingDepositTier>> listTiers(@RequestParam(required = false) String applicantType) {
        if (applicantType != null) {
            PrincipalType.of(applicantType);
            return ApiResult.ok(tierRepository.findByApplicantTypeOrderBySortNoAsc(applicantType));
        }
        return ApiResult.ok(tierRepository.findAll());
    }

    /** 新增 / 更新档位（保证金金额 / 授信倍率 / 绝对额度覆盖 —— 倍率可配，不硬编码）。 */
    @PostMapping("/deposit-tiers")
    public ApiResult<OnboardingDepositTier> upsertTier(@RequestBody TierReq req) {
        OnboardingDepositTier tier = req.id() == null
                ? OnboardingDepositTier.builder().applicantType(req.applicantType()).tierCode(req.tierCode()).build()
                : tierRepository.findById(req.id())
                        .orElseThrow(() -> new com.claw.server.common.api.BizException(
                                40401, "onboarding.deposit.tier.not.found"));
        if (req.tierName() != null) {
            tier.setTierName(req.tierName());
        }
        if (req.depositAmount() != null) {
            tier.setDepositAmount(req.depositAmount());
        }
        if (req.creditMultiplier() != null) {
            tier.setCreditMultiplier(req.creditMultiplier());
        }
        if (req.creditLimitOverride() != null) {
            tier.setCreditLimitOverride(req.creditLimitOverride());
        }
        if (req.benefitDesc() != null) {
            tier.setBenefitDesc(req.benefitDesc());
        }
        if (req.sortNo() != null) {
            tier.setSortNo(req.sortNo());
        }
        if (req.enabled() != null) {
            tier.setEnabled(req.enabled());
        }
        tier.setUpdatedAt(java.time.Instant.now());
        return ApiResult.ok(tierRepository.save(tier));
    }

    /* ---------------- 保证金缴纳确认（O19） ---------------- */

    @GetMapping("/deposits")
    public ApiResult<List<OnboardingDeposit>> listDeposits(@RequestParam(required = false) String status) {
        return ApiResult.ok(depositService.listByStatus(status));
    }

    /** 确认到账 → 触发自动激活（O20）。 */
    @PostMapping("/deposits/{id}/confirm")
    public ApiResult<?> confirmDeposit(@PathVariable Long id) {
        return ApiResult.ok(depositService.confirm(id, currentUserId()));
    }

    /** 驳回缴款凭证（重传）。 */
    @PostMapping("/deposits/{id}/reject")
    public ApiResult<OnboardingDeposit> rejectDeposit(@PathVariable Long id, @RequestBody RejectReq req) {
        return ApiResult.ok(depositService.reject(id, req.reason(), currentUserId()));
    }

    /* ---------------- 附件审核（B2） ---------------- */

    @PostMapping("/attachments/{attachmentId}/review")
    public ApiResult<?> reviewAttachment(@PathVariable Long attachmentId, @RequestBody AttachReviewReq req) {
        return ApiResult.ok(attachmentService.reviewItem(attachmentId, req.reviewStatus(), req.reviewRemark()));
    }

    private static Long currentUserId() {
        Long uid = AuthContext.currentUserId();
        if (uid == null) {
            throw new com.claw.server.common.api.BizException(40301, "login.required");
        }
        return uid;
    }

    /** 审核请求（含逐材料项结论）。 */
    public record ReviewReq(String action, String reason,
                            List<OnboardingApplicationService.MaterialReviewResult> items) {
    }

    /** 身份证认证结果请求。 */
    public record KycReq(String kycStatus, Long kycRecordId) {
    }

    /** 合同请求。 */
    public record ContractReq(Long id, String applicantType, String lang, String title,
                              String contentHtml, String contractFileUrl, Boolean major) {
    }

    /** 材料清单请求。 */
    public record MaterialReq(Long id, String applicantType, String materialCode, String materialName,
                              String inputType, Boolean required, Integer minCount, Integer maxCount,
                              String hint, Integer sortNo) {
    }

    /** 档位请求。 */
    public record TierReq(Long id, String applicantType, String tierCode, String tierName,
                          BigDecimal depositAmount, BigDecimal creditMultiplier,
                          BigDecimal creditLimitOverride, String benefitDesc,
                          Integer sortNo, Boolean enabled) {
    }

    /** 缴款驳回请求。 */
    public record RejectReq(String reason) {
    }

    /** 附件审核请求。 */
    public record AttachReviewReq(String reviewStatus, String reviewRemark) {
    }
}
