package com.claw.server.domain.onboarding;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.OnboardingApplicationStatus;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.common.security.IdCardCipher;
import com.claw.server.domain.onboarding.OnboardingApplicationLog.Action;
import com.claw.server.domain.onboarding.OnboardingApplicationLog.OperatorType;
import com.claw.server.domain.org.OrgWritableGuard;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 入驻申请单服务（增量 C · O6–O17 核心）。
 *
 * <p>三类主体<b>共用同一张表与同一套状态机</b>（G1），差异只在材料清单配置。
 * 状态迁移一律走 {@link OnboardingApplicationStatus#assertTransition} 白名单，
 * 非法组合抛 {@code onboarding.status.illegal}。
 *
 * <p>草稿支持分步保存（O9）：{@link #saveDraft} 不做必填校验，{@link #submit} 才做全量校验。
 * 驳回 / 退回后状态回到 DRAFT，申请人在<b>原表单</b>改完直接重提，不回退空白（B2）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingApplicationService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int NO_SEQ_BOUND = 10000;

    private final OnboardingApplicationRepository applicationRepository;
    private final OnboardingContractRepository contractRepository;
    private final OnboardingAttachmentRepository attachmentRepository;
    private final OnboardingAttachmentService attachmentService;
    private final OnboardingContractService contractService;
    private final OnboardingMaterialService materialService;
    private final OnboardingApplicationLogService logService;
    private final OnboardingDepositTierRepository tierRepository;
    private final OnboardingDepositRepository depositRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final IdCardCipher idCardCipher;
    private final OrgWritableGuard orgWritableGuard;
    private final ObjectMapper objectMapper;

    /* ------------------------------------------------------------------ */
    /* 草稿：创建 / 保存                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * 新建草稿。同一用户 + 同一主体类型同时最多 1 条非终态申请
     * （由 {@code uq_onb_app_active} 部分唯一索引兜底）。
     */
    @Transactional
    public OnboardingApplication createDraft(Long userId, String applicantType, String lang) {
        PrincipalType.of(applicantType);   // 校验主体类型合法
        if (findActiveDraft(userId, applicantType).isPresent()) {
            throw BizException.of(40911, "onboarding.application.active.exists");
        }
        OnboardingApplication app = OnboardingApplication.builder()
                .applicationNo(generateApplicationNo())
                .applicantType(applicantType)
                .applicantUserId(userId)
                .status(OnboardingApplicationStatus.DRAFT.name())
                .kycStatus("PENDING")
                .contractVersion("v1.0")
                .build();
        OnboardingApplication saved = applicationRepository.save(app);
        logService.record(saved.getId(), null, OnboardingApplicationStatus.DRAFT.name(), Action.CREATE,
                userId, OperatorType.APPLICANT, "创建入驻申请草稿");
        log.info("创建入驻申请草稿 id={} no={} type={} user={}", saved.getId(), saved.getApplicationNo(),
                applicantType, userId);
        return saved;
    }

    /** 取当前非终态申请（草稿 / 审批中 / 待缴款），无则返回 empty。 */
    @Transactional(readOnly = true)
    public Optional<OnboardingApplication> findActiveDraft(Long userId, String applicantType) {
        for (String st : OnboardingApplicationStatus.activeStatusNames()) {
            Optional<OnboardingApplication> found =
                    applicationRepository.findByApplicantUserIdAndApplicantTypeAndStatus(userId, applicantType, st);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** 取或建草稿（前端「继续填写」入口）。 */
    @Transactional
    public OnboardingApplication getOrCreateDraft(Long userId, String applicantType, String lang) {
        return findActiveDraft(userId, applicantType)
                .orElseGet(() -> createDraft(userId, applicantType, lang));
    }

    /**
     * 保存草稿：<b>不做必填校验</b>，只落库（支持分步填写，O9）。
     *
     * @return 保存后的申请单
     */
    @Transactional
    public OnboardingApplication saveDraft(Long userId, OnboardingFormReq req) {
        OnboardingApplication app = resolveTarget(userId, req);
        applyForm(app, req);
        OnboardingApplication saved = applicationRepository.save(app);
        logService.record(saved.getId(), null, saved.getStatus(), Action.SAVE_DRAFT,
                userId, OperatorType.APPLICANT, "保存草稿");
        return saved;
    }

    /* ------------------------------------------------------------------ */
    /* 提交 / 校验                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 提交申请：全量必填校验 + 合同版本快照 + 勾选同意（O3）。
     *
     * <p>校验项：
     * <ol>
     *   <li>全部必填文本材料非空；</li>
     *   <li>全部必填附件数量满足 min/max（如场地照片 3–9 张）；</li>
     *   <li>已勾选同意协议（{@code agreed=true}）；</li>
     *   <li>已选择保证金档位；</li>
     *   <li>合同版本存在且为 PUBLISHED —— 并<b>快照版本号</b>到申请单（B5）。</li>
     * </ol>
     *
     * @throws BizException 10001 onboarding.submit.validation.failed（errors 明细在 data 里）
     */
    @Transactional
    public OnboardingApplication submit(Long userId, OnboardingFormReq req) {
        PrincipalType.of(req.applicantType());
        // Q7：已禁用主体不可再申请同类（若该用户已绑定某被禁用主体）
        orgWritableGuard.assertApplicantCanApply(userId, req.applicantType());

        OnboardingApplication app = resolveTarget(userId, req);
        if (app.status() != OnboardingApplicationStatus.DRAFT) {
            throw BizException.of(40940, "onboarding.status.illegal", app.getStatus(), "SUBMITTED");
        }
        applyForm(app, req);

        // —— 合同：锁定当前生效版本并快照版本号（争议以签署时版本为准）——
        String lang = (req.lang() == null || req.lang().isBlank()) ? "zh" : req.lang();
        OnboardingContract contract = req.contractId() != null
                ? contractService.listVersions(req.applicantType(), lang).stream()
                        .filter(c -> c.getId().equals(req.contractId()))
                        .findFirst()
                        .orElseThrow(() -> BizException.of(40401, "onboarding.contract.not.found"))
                : contractService.requirePublished(req.applicantType(), lang);
        if (!contract.isPublished()) {
            throw BizException.of(40940, "onboarding.contract.not.published");
        }
        if (!Boolean.TRUE.equals(req.agreed())) {
            throw BizException.of(10001, "onboarding.agreement.not.agreed");
        }

        // —— 全量校验 ——
        List<OnboardingMaterialRequirement> requirements =
                materialService.listEnabled(req.applicantType());
        List<String> errors = validate(requirements, req);
        if (req.depositTierId() == null) {
            errors.add("保证金档位：必选");
        } else {
            OnboardingDepositTier tier = tierRepository.findById(req.depositTierId())
                    .orElseThrow(() -> BizException.of(40401, "onboarding.deposit.tier.not.found"));
            if (!tier.getApplicantType().equals(req.applicantType())) {
                errors.add("保证金档位：与主体类型不匹配");
            }
            if (!Boolean.TRUE.equals(tier.getEnabled())) {
                errors.add("保证金档位：该档位已停用");
            }
        }
        if (!errors.isEmpty()) {
            throw BizException.of(10001, "onboarding.submit.validation.failed", String.join("；", errors));
        }

        // —— 落库 + 状态迁移 DRAFT → SUBMITTED → REVIEWING ——
        app.setContractId(contract.getId());
        app.setContractVersion(contract.getVersion());
        app.setAgreedAt(Instant.now());
        app.setSubmittedAt(Instant.now());
        Instant now = Instant.now();
        app.setExpireAt(now.plusSeconds(readConfigInt("ONBOARDING_DEPOSIT_PAY_DAYS", 15) * 86400L));
        String from = app.getStatus();
        app.transitTo(OnboardingApplicationStatus.SUBMITTED);
        // 平台自动受理（SUBMITTED → REVIEWING），列表直接显示「待审」
        app.transitTo(OnboardingApplicationStatus.REVIEWING);
        OnboardingApplication saved = applicationRepository.save(app);
        logService.record(saved.getId(), from, OnboardingApplicationStatus.SUBMITTED.name(), Action.SUBMIT,
                userId, OperatorType.APPLICANT, "提交申请，合同版本 " + contract.getVersion());
        logService.record(saved.getId(), OnboardingApplicationStatus.SUBMITTED.name(),
                OnboardingApplicationStatus.REVIEWING.name(), Action.SUBMIT, null, OperatorType.SYSTEM,
                "平台自动受理，进入待审");
        log.info("入驻申请提交 id={} no={} type={} contract={}", saved.getId(), saved.getApplicationNo(),
                req.applicantType(), contract.getVersion());
        return saved;
    }

    /**
     * 全量校验材料清单。
     *
     * @return 错误明细列表（空 = 校验通过）
     */
    public List<String> validate(List<OnboardingMaterialRequirement> requirements, OnboardingFormReq req) {
        List<String> errors = new ArrayList<>();
        boolean needLocation = false;
        for (OnboardingMaterialRequirement r : requirements) {
            String code = r.getMaterialCode();
            // LOCATION 是坐标型材料，没有文本值也没有附件，走下方 lat/lng 专项校验
            if ("LOCATION".equals(code)) {
                needLocation = Boolean.TRUE.equals(r.getRequired());
                continue;
            }
            boolean isAttachment = isAttachmentType(r.getInputType());
            int count = isAttachment ? req.files(code).size() : (hasText(req.field(code)) ? 1 : 0);
            materialService.validateCount(r, count).ifPresent(errors::add);
        }
        if (needLocation && (req.lat() == null || req.lng() == null)) {
            errors.add("场地定位：必填（请在地图上点选场地位置）");
        }
        return errors;
    }

    /* ------------------------------------------------------------------ */
    /* 平台初审（O16 / B2）                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * 平台初审：通过 / 驳回 / 退回补正，支持逐材料项结论。
     *
     * @param applicationId 申请单 id
     * @param action        APPROVE / REJECT / RETURN
     * @param reason        审核意见（驳回 / 退回时必填）
     * @param items         逐材料项结论：{@code attachmentId → {reviewStatus, reviewRemark}}
     * @param operatorId    平台操作人
     */
    @Transactional
    public OnboardingApplication review(Long applicationId, String action, String reason,
                                        List<MaterialReviewResult> items, Long operatorId) {
        OnboardingApplication app = load(applicationId);
        String from = app.getStatus();
        if (!"APPROVE".equals(action) && !"REJECT".equals(action) && !"RETURN".equals(action)) {
            throw BizException.of(10001, "onboarding.review.action.invalid", String.valueOf(action));
        }
        if (!"APPROVE".equals(action) && (reason == null || reason.isBlank())) {
            throw BizException.of(10001, "onboarding.review.reason.required");
        }
        // 逐材料项结论（B2：驳回精确到材料项）
        for (MaterialReviewResult item : (items == null ? List.<MaterialReviewResult>of() : items)) {
            attachmentService.reviewItem(item.attachmentId(), item.reviewStatus(), item.reviewRemark());
        }
        switch (action) {
            case "APPROVE" -> {
                app.transitTo(OnboardingApplicationStatus.APPROVED);
                app.setReviewedAt(Instant.now());
                app.setApprovedAt(Instant.now());
                app.setReviewedBy(operatorId);
                app.setRejectReason(null);
                app.setExpireAt(Instant.now()
                        .plusSeconds(readConfigInt("ONBOARDING_DEPOSIT_PAY_DAYS", 15) * 86400L));
            }
            case "REJECT" -> {
                app.transitTo(OnboardingApplicationStatus.REJECTED);
                app.setReviewedAt(Instant.now());
                app.setReviewedBy(operatorId);
                app.setRejectReason(reason);
            }
            case "RETURN" -> {
                app.transitTo(OnboardingApplicationStatus.RETURNED);
                app.setReviewedAt(Instant.now());
                app.setReviewedBy(operatorId);
                app.setRejectReason(reason);
            }
            default -> throw BizException.of(10001, "onboarding.review.action.invalid", String.valueOf(action));
        }
        OnboardingApplication saved = applicationRepository.save(app);
        logService.record(saved.getId(), from, saved.getStatus(),
                "APPROVE".equals(action) ? Action.APPROVE : "REJECT".equals(action) ? Action.REJECT : Action.RETURN,
                operatorId, OperatorType.PLATFORM, reason, toJson(items));
        log.info("入驻申请审核 id={} action={} operator={}", applicationId, action, operatorId);
        return saved;
    }

    /** 人工置身份证认证结果（Q5：P0 人工审核，P1 接 CamDigiKey）。 */
    @Transactional
    public OnboardingApplication setKycStatus(Long applicationId, String kycStatus, Long kycRecordId,
                                              Long operatorId) {
        OnboardingApplication app = load(applicationId);
        if (!"PENDING".equals(kycStatus) && !"VERIFIED".equals(kycStatus) && !"REJECTED".equals(kycStatus)) {
            throw BizException.of(10001, "onboarding.kyc.status.invalid", String.valueOf(kycStatus));
        }
        app.setKycStatus(kycStatus);
        if (kycRecordId != null) {
            app.setKycRecordId(kycRecordId);
        }
        OnboardingApplication saved = applicationRepository.save(app);
        logService.record(saved.getId(), null, saved.getStatus(), Action.SUBMIT, operatorId,
                OperatorType.PLATFORM, "身份证认证结果置为 " + kycStatus);
        return saved;
    }

    /** 申请人撤回（DRAFT / SUBMITTED / REVIEWING / APPROVED 可撤回）。 */
    @Transactional
    public OnboardingApplication cancel(Long applicationId, Long userId) {
        OnboardingApplication app = load(applicationId);
        if (!app.getApplicantUserId().equals(userId)) {
            throw BizException.of(40301, "onboarding.application.not.owner");
        }
        String from = app.getStatus();
        app.transitTo(OnboardingApplicationStatus.CANCELLED);
        OnboardingApplication saved = applicationRepository.save(app);
        logService.record(saved.getId(), from, OnboardingApplicationStatus.CANCELLED.name(), Action.CANCEL,
                userId, OperatorType.APPLICANT, "申请人撤回");
        log.info("入驻申请撤回 id={} user={}", applicationId, userId);
        return saved;
    }

    /** 缴款超时扫描：APPROVED 且已过 expire_at 的申请转 EXPIRED（O21）。 */
    @Transactional
    public int expireOverdue() {
        List<OnboardingApplication> overdue = applicationRepository
                .findByStatusAndExpireAtBefore(OnboardingApplicationStatus.APPROVED.name(), Instant.now());
        for (OnboardingApplication app : overdue) {
            app.transitTo(OnboardingApplicationStatus.EXPIRED);
            applicationRepository.save(app);
            logService.record(app.getId(), OnboardingApplicationStatus.APPROVED.name(),
                    OnboardingApplicationStatus.EXPIRED.name(), Action.EXPIRE, null, OperatorType.SYSTEM,
                    "保证金缴款超时，申请自动失效");
        }
        if (!overdue.isEmpty()) {
            log.info("入驻申请缴款超时扫描：{} 条转 EXPIRED", overdue.size());
        }
        return overdue.size();
    }

    /** 重新激活已超时的申请（EXPIRED → APPROVED）。 */
    @Transactional
    public OnboardingApplication reactivate(Long applicationId, Long userId) {
        OnboardingApplication app = load(applicationId);
        if (!app.getApplicantUserId().equals(userId)) {
            throw BizException.of(40301, "onboarding.application.not.owner");
        }
        String from = app.getStatus();
        app.transitTo(OnboardingApplicationStatus.APPROVED);
        app.setExpireAt(Instant.now().plusSeconds(readConfigInt("ONBOARDING_DEPOSIT_PAY_DAYS", 15) * 86400L));
        OnboardingApplication saved = applicationRepository.save(app);
        logService.record(saved.getId(), from, OnboardingApplicationStatus.APPROVED.name(), Action.SUBMIT,
                userId, OperatorType.APPLICANT, "重新激活申请，恢复待缴款");
        return saved;
    }

    /* ------------------------------------------------------------------ */
    /* 查询                                                                */
    /* ------------------------------------------------------------------ */

    /** 我的全部申请（新在前）。 */
    @Transactional(readOnly = true)
    public List<OnboardingApplication> listMine(Long userId) {
        return maskAll(applicationRepository.findByApplicantUserIdOrderByCreatedAtDesc(userId));
    }

    /** 我的某主体类型申请。 */
    @Transactional(readOnly = true)
    public List<OnboardingApplication> listMineByType(Long userId, String applicantType) {
        return maskAll(applicationRepository
                .findByApplicantUserIdAndApplicantTypeOrderByCreatedAtDesc(userId, applicantType));
    }

    /**
     * 平台侧列表（按主体类型 / 状态筛选；两个条件都为 null 时返回全部）。
     *
     * <p>只传了主体类型时返回该类主体的<b>全部</b>申请 —— 不悄悄限定为「待审」，
     * 否则管理页「主体类型=服务站 + 状态=全部」会漏数据。
     */
    @Transactional(readOnly = true)
    public List<OnboardingApplication> listForReview(String applicantType, String status) {
        if (applicantType != null && status != null) {
            return maskAll(applicationRepository
                    .findByApplicantTypeAndStatusOrderByCreatedAtDesc(applicantType, status));
        }
        if (status != null) {
            return maskAll(applicationRepository.findByStatusOrderByCreatedAtDesc(status));
        }
        if (applicantType != null) {
            return maskAll(applicationRepository.findAll().stream()
                    .filter(a -> applicantType.equals(a.getApplicantType()))
                    .sorted(java.util.Comparator.comparing(OnboardingApplication::getCreatedAt).reversed())
                    .toList());
        }
        return maskAll(applicationRepository.findAll());
    }

    /** 详情（一屏决策，O35）：身份证脱敏 + 全部材料 + 时间轴 + 合同版本 + 档位 + 缴款。 */
    @Transactional(readOnly = true)
    public ApplicationDetail detail(Long applicationId) {
        OnboardingApplication app = load(applicationId);
        String masked = idCardCipher.mask(app.getIdCardNo());
        app.setIdCardNo(null);   // readOnly 事务不会 flush，安全
        OnboardingContract contract = app.getContractId() == null ? null
                : contractRepository.findById(app.getContractId()).orElse(null);
        OnboardingDepositTier tier = app.getDepositTierId() == null ? null
                : tierRepository.findById(app.getDepositTierId()).orElse(null);
        return new ApplicationDetail(
                app,
                masked,
                attachmentRepository.findByApplicationIdOrderByAttachTypeAscSortNoAsc(applicationId),
                materialService.listAll(app.getApplicantType()),
                logService.timeline(applicationId),
                contract,
                tier,
                depositRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId),
                app.getPrincipalId() == null ? null
                        : orgWritableGuard.orgOnboardingStatus(app.getApplicantType(), app.getPrincipalId()));
    }

    @Transactional(readOnly = true)
    public OnboardingApplication load(Long applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.application.not.found"));
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                            */
    /* ------------------------------------------------------------------ */

    /** 定位草稿：有 applicationId 用它，否则用「当前非终态申请」，都没有则新建。 */
    private OnboardingApplication resolveTarget(Long userId, OnboardingFormReq req) {
        if (req.applicationId() != null) {
            OnboardingApplication app = load(req.applicationId());
            if (!app.getApplicantUserId().equals(userId)) {
                throw BizException.of(40301, "onboarding.application.not.owner");
            }
            return app;
        }
        return getOrCreateDraft(userId, req.applicantType(), req.lang());
    }

    /**
     * 把表单内容写入申请单实体。
     *
     * <p>材料码 → 列的映射：APPLICANT_NAME→applicantName、CONTACT→contactPhone、
     * HOME_ADDRESS→homeAddress、ID_CARD→idCardNo（<b>加密存储</b>）、
     * BUSINESS_SCOPE→businessScope、LAND_INTRO→landIntro、COOPERATION_PLAN→cooperationPlan、
     * MERCH_CATEGORY→追加到 businessScope（商家「经营品类」无独立列，与经营范围合并展示）。
     */
    private void applyForm(OnboardingApplication app, OnboardingFormReq req) {
        if (req.applicantType() != null) {
            app.setApplicantType(req.applicantType());
        }
        app.setApplicantName(req.field("APPLICANT_NAME"));
        app.setContactPhone(req.field("CONTACT"));
        app.setHomeAddress(req.field("HOME_ADDRESS"));
        String idCard = req.field("ID_CARD");
        if (idCard != null && !idCard.isEmpty()) {
            app.setIdCardNo(idCardCipher.encrypt(idCard));
        }
        String businessScope = req.field("BUSINESS_SCOPE");
        String merchCategory = req.field("MERCH_CATEGORY");
        if (businessScope != null) {
            app.setBusinessScope(merchCategory == null || merchCategory.isEmpty()
                    ? businessScope
                    : businessScope + " / 经营品类：" + merchCategory);
        } else if (merchCategory != null) {
            app.setBusinessScope("经营品类：" + merchCategory);
        }
        app.setLandIntro(req.field("LAND_INTRO"));
        app.setCooperationPlan(req.field("COOPERATION_PLAN"));
        if (req.ownershipType() != null) {
            app.setOwnershipType(req.ownershipType());
        }
        if (req.lat() != null) {
            app.setLat(req.lat());
            app.setLocatedAt(Instant.now());
        }
        if (req.lng() != null) {
            app.setLng(req.lng());
        }
        if (req.geoAddress() != null) {
            app.setGeoAddress(req.geoAddress());
        }
        if (req.depositTierId() != null) {
            app.setDepositTierId(req.depositTierId());
        }
        app.setUpdatedAt(Instant.now());

        // 附件类材料：整组替换（申请人在原表单改完重提，不残留旧文件）
        for (Map.Entry<String, List<String>> e : req.safeAttachments().entrySet()) {
            attachmentService.replaceAll(app.getId(), e.getKey(), e.getValue(), null, app.getApplicantUserId());
        }
    }

    /** 生成申请单号 ONB{yyyyMMdd}{4位序号}，冲突重试。 */
    private String generateApplicationNo() {
        String date = NO_DATE.format(Instant.now().atZone(ZoneOffset.UTC));
        for (int i = 0; i < 10; i++) {
            String no = "ONB" + date + String.format("%04d", ThreadLocalRandom.current().nextInt(NO_SEQ_BOUND));
            if (applicationRepository.findByApplicationNo(no).isEmpty()) {
                return no;
            }
        }
        throw new DataIntegrityViolationException("无法生成唯一的入驻申请单号");
    }

    private static boolean isAttachmentType(String inputType) {
        return "IMAGE".equals(inputType) || "IMAGES".equals(inputType) || "FILE".equals(inputType);
    }

    private static boolean hasText(String v) {
        return v != null && !v.isEmpty();
    }

    /** 列表/详情统一脱敏：直查数据库返回的实体在 readOnly 事务中修改，不会被 flush。 */
    private List<OnboardingApplication> maskAll(List<OnboardingApplication> apps) {
        for (OnboardingApplication app : apps) {
            if (app.getIdCardNo() != null) {
                app.setIdCardNo(idCardCipher.mask(app.getIdCardNo()));
            }
        }
        return apps;
    }

    private int readConfigInt(String key, int fallback) {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> {
                    try {
                        return Integer.parseInt(v.trim());
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /** 逐材料项审核结论（O16 / B2）。 */
    public record MaterialReviewResult(Long attachmentId, String reviewStatus, String reviewRemark) {
    }
}
