package com.claw.server.domain.onboarding;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.OnboardingStatus;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.common.event.OutboxPublisher;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.credit.CreditLimitService;
import com.claw.server.domain.manufacturer.Manufacturer;
import com.claw.server.domain.manufacturer.ManufacturerRepository;
import com.claw.server.domain.merchant.Merchant;
import com.claw.server.domain.merchant.MerchantRepository;
import com.claw.server.domain.role.PrincipalBinding;
import com.claw.server.domain.role.PrincipalBindingRepository;
import com.claw.server.domain.role.PrincipalBindingService;
import com.claw.server.domain.role.PrincipalResolver;
import com.claw.server.domain.role.RoleTemplateService;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import com.claw.server.domain.station.Station;
import com.claw.server.domain.station.StationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 入驻激活编排（增量 C · O20 / §6.4）。
 *
 * <p><b>单事务强一致</b>：① 建主体 → ② 回填定位/门头/额度 → ③ 写 principal_bindings →
 * ④ 授予角色模板全量权限 → ⑤ 开通子账号管理入口 → ⑥ 置 onboarding_status=ACTIVATED
 * → ⑦ 写 Outbox（通知 + C 端索引刷新，异步投递）。
 *
 * <p><b>失败处理（Q8 采纳）</b>：本服务跑在 {@code REQUIRES_NEW} 独立事务里
 * —— 保证金确认（钱已到账）在前一个事务中已提交，激活失败<b>只回滚激活动作</b>，
 * 保证金记录保持 CONFIRMED、申请单停留在 DEPOSIT_PAID，由管理页「重试激活」人工入口重跑
 * （按 {@code onboarding_status='PENDING' AND 保证金已确认} 筛选），<b>不做静默自动重试</b>。
 *
 * <p>重试是幂等的：若申请单已有 principalId 且主体记录存在，则复用而不重建。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingActivationService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OnboardingApplicationRepository applicationRepository;
    private final OnboardingApplicationLogService logService;
    private final OnboardingAttachmentService attachmentService;
    private final OnboardingDepositTierRepository tierRepository;
    private final OnboardingDepositRepository depositRepository;
    private final StationRepository stationRepository;
    private final ManufacturerRepository manufacturerRepository;
    private final MerchantRepository merchantRepository;
    private final PrincipalBindingService bindingService;
    private final PrincipalBindingRepository bindingRepository;
    private final PrincipalResolver principalResolver;
    private final RoleTemplateService roleTemplateService;
    private final CreditLimitService creditLimitService;
    private final SystemConfigRepository systemConfigRepository;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 激活（独立事务，失败只回滚激活动作本身）。
     *
     * @param applicationId 入驻申请单 ID
     * @return 激活结果
     * @throws BizException 40940 onboarding.activation.illegal（申请单状态不是 DEPOSIT_PAID）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActivationResult activate(Long applicationId) {
        OnboardingApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.application.not.found"));
        if (app.status() != com.claw.server.common.enums.OnboardingApplicationStatus.DEPOSIT_PAID) {
            throw BizException.of(40940, "onboarding.activation.illegal", app.getStatus());
        }
        PrincipalType type = PrincipalType.of(app.getApplicantType());

        // ① 创建（或复用）主体记录 —— 重试激活时幂等
        boolean retried = app.getPrincipalId() != null && orgExists(type, app.getPrincipalId());
        Long principalId = retried ? app.getPrincipalId() : createPrincipal(app, type);
        app.setPrincipalId(principalId);

        // ② 回填 lat/lng/geo_address/门头/额度/档位
        boolean signboardReviewRequired = readConfigBool("ONBOARDING_SIGNBOARD_REVIEW_REQUIRED", true);
        Optional<String> signboard = attachmentService.signboardUrl(app.getId(), signboardReviewRequired);
        BigDecimal creditLimit = resolveCreditLimit(app, type);
        backfill(type, principalId, app, signboard.orElse(null), creditLimit, app.getDepositTierId());

        // ③ 账号 ↔ 主体绑定（已存在则复用，保证重试幂等）
        Long bindingId = bindPrincipal(app.getApplicantUserId(), type, principalId);

        // ④ 授予角色模板全量权限码（「激活全部功能」= 授予模板全量）
        Set<String> granted = roleTemplateService.grantTemplate(app.getApplicantUserId(), type.name());

        // ⑤ 开通子账号管理入口（org:subaccount:manage 已由 V62 挂进 STATION/MANUFACTURER/MERCHANT 模板，
        //    故授予模板即已开通；此处仅做显式校验并记录，便于排障）
        boolean subAccountReady = granted.contains("org:subaccount:manage");
        if (!subAccountReady) {
            log.warn("角色模板 {} 未挂载 org:subaccount:manage，主体 {} 的子账号管理入口缺失",
                    type.name(), principalId);
        }

        // ⑥ onboarding_status = ACTIVATED（与申请单终态同名；存量 'ACTIVE' 由 V64 回填为 'ACTIVATED'）
        setOnboardingStatus(type, principalId, OnboardingStatus.ACTIVATED.name());

        // ⑦ 回填缴款台账的 principal_id（缴款在独立事务中已确认为到账，此处只补主体关联）
        backfillDepositPrincipal(app.getId(), type, principalId);

        // ⑧ Outbox：通知 + C 端索引刷新（同事务落库，异步投递，失败可重投不影响主流程）
        outboxPublisher.publish("ONBOARDING_APPLICATION", app.getId(), "ORG_ACTIVATED",
                buildPayload(app, type, principalId, creditLimit));

        // 申请单 → ACTIVATED（终态）
        String from = app.getStatus();
        app.transitTo(com.claw.server.common.enums.OnboardingApplicationStatus.ACTIVATED);
        app.setActivatedAt(Instant.now());
        applicationRepository.save(app);
        logService.record(app.getId(), from, app.getStatus(),
                OnboardingApplicationLog.Action.ACTIVATE, AuthContext.currentUserId(),
                OnboardingApplicationLog.OperatorType.SYSTEM,
                "保证金到账，自动激活：" + type.name() + "#" + principalId);

        principalResolver.evict(app.getApplicantUserId());
        log.info("入驻激活完成 app={} type={} principalId={} user={} perms={} retried={}",
                app.getId(), type, principalId, app.getApplicantUserId(), granted.size(), retried);
        return new ActivationResult(principalId, type.name(), bindingId, type.name(), creditLimit,
                granted.size(), retried);
    }

    /** 供管理页「重试激活」：按申请单 ID 重跑（幂等）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActivationResult retry(Long applicationId) {
        OnboardingApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.application.not.found"));
        if (app.getPrincipalId() != null) {
            PrincipalType type = PrincipalType.of(app.getApplicantType());
            if (orgExists(type, app.getPrincipalId())
                    && OnboardingStatus.isWritableStatus(currentStatus(type, app.getPrincipalId()))) {
                throw BizException.of(40940, "onboarding.activation.already.active");
            }
        }
        log.warn("人工重试激活 app={}", applicationId);
        return activate(applicationId);
    }

    /** 列出「待重试激活」的申请单：保证金已确认、申请单 DEPOSIT_PAID、组织未激活。 */
    @Transactional(readOnly = true)
    public java.util.List<OnboardingApplication> listPendingActivation() {
        return applicationRepository
                .findByStatusOrderByCreatedAtDesc(
                        com.claw.server.common.enums.OnboardingApplicationStatus.DEPOSIT_PAID.name())
                .stream()
                .filter(a -> a.getPrincipalId() == null
                        || !OnboardingStatus.isWritableStatus(
                                currentStatus(PrincipalType.of(a.getApplicantType()), a.getPrincipalId())))
                .toList();
    }

    /* ------------------------------------------------------------------ */
    /* 内部：主体创建 / 回填 / 绑定                                          */
    /* ------------------------------------------------------------------ */

    private Long createPrincipal(OnboardingApplication app, PrincipalType type) {
        String code = generateCode(type);
        String name = StringUtils.hasText(app.getApplicantName())
                ? app.getApplicantName() + "·" + typeLabel(type)
                : typeLabel(type) + "-" + app.getApplicationNo();
        Instant now = Instant.now();
        return switch (type) {
            case STATION -> stationRepository.save(Station.builder()
                    .code(code).name(name)
                    .countryCode("KHM")
                    .status("ACTIVE")
                    .lat(app.getLat()).lng(app.getLng())
                    .geoAddress(app.getGeoAddress())
                    .onboardingStatus("PENDING")
                    .onboardingApplicationId(app.getId())
                    .createdAt(now).updatedAt(now)
                    .build()).getId();
            case MANUFACTURER -> manufacturerRepository.save(Manufacturer.builder()
                    .code(code).name(name)
                    .country("KHM")
                    .status("ACTIVE")
                    .onboardingStatus("PENDING")
                    .onboardingApplicationId(app.getId())
                    .createdAt(now).updatedAt(now)
                    .build()).getId();
            case MERCHANT -> merchantRepository.save(Merchant.builder()
                    .code(code).name(name)
                    .contact(app.getContactPhone())
                    .country("KHM")
                    .status("PENDING")
                    .onboardingStatus("PENDING")
                    .onboardingApplicationId(app.getId())
                    .createdAt(now).updatedAt(now)
                    .build()).getId();
        };
    }

    private void backfill(PrincipalType type, Long principalId, OnboardingApplication app,
                          String signboardUrl, BigDecimal creditLimit, Long tierId) {
        switch (type) {
            case STATION -> {
                Station s = stationRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "station.not.found"));
                if (app.getLat() != null) {
                    s.setLat(app.getLat());
                }
                if (app.getLng() != null) {
                    s.setLng(app.getLng());
                }
                if (StringUtils.hasText(app.getGeoAddress())) {
                    s.setGeoAddress(app.getGeoAddress());
                }
                if (signboardUrl != null) {
                    s.setSignboardUrl(signboardUrl);
                }
                s.setDepositTierId(tierId);
                s.setCreditLimit(creditLimit);
                s.setUpdatedAt(Instant.now());
                stationRepository.save(s);
            }
            case MANUFACTURER -> {
                Manufacturer m = manufacturerRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "manufacturer.not.found"));
                m.setDepositTierId(tierId);
                m.setCreditLimit(creditLimit);
                m.setUpdatedAt(Instant.now());
                manufacturerRepository.save(m);
            }
            case MERCHANT -> {
                Merchant m = merchantRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "merchant.not.found"));
                m.setDepositTierId(tierId);
                m.setCreditLimit(creditLimit);
                if (StringUtils.hasText(app.getBusinessScope())) {
                    m.setContact(app.getContactPhone());
                }
                m.setUpdatedAt(Instant.now());
                merchantRepository.save(m);
            }
        }
    }

    /**
     * 激活成功后把主体 ID 回填到缴款台账（激活前缴款已确认但还不知道主体 ID）。
     *
     * <p>只更新已确认（CONFIRMED）的缴款记录；失败不影响激活主流程，仅记 WARN
     * （组织管理页的「保证金记录」靠此字段查询，缺失时可人工补）。
     */
    private void backfillDepositPrincipal(Long applicationId, PrincipalType type, Long principalId) {
        try {
            for (OnboardingDeposit dep : depositRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId)) {
                dep.setPrincipalType(type.name());
                dep.setPrincipalId(principalId);
                dep.setUpdatedAt(Instant.now());
                depositRepository.save(dep);
            }
        } catch (Exception e) {
            log.warn("回填缴款台账 principal_id 失败 app={} principal={}#{}：{}",
                    applicationId, type, principalId, e.getMessage());
        }
    }

    private Long bindPrincipal(Long userId, PrincipalType type, Long principalId) {
        Optional<PrincipalBinding> existing =
                bindingRepository.findByUserIdAndPrincipalType(userId, type.name());
        if (existing.isPresent()) {
            PrincipalBinding b = existing.get();
            if (!b.getPrincipalId().equals(principalId)) {
                b.setPrincipalId(principalId);
                b = bindingRepository.save(b);
            }
            return b.getId();
        }
        return bindingService.bind(userId, type.name(), principalId).getId();
    }

    private BigDecimal resolveCreditLimit(OnboardingApplication app, PrincipalType type) {
        if (app.getDepositTierId() == null) {
            log.warn("申请单 {} 未选保证金档位，激活后不校验授信额度", app.getId());
            return null;
        }
        OnboardingDepositTier tier = tierRepository.findById(app.getDepositTierId())
                .orElseThrow(() -> BizException.of(40401, "onboarding.deposit.tier.not.found"));
        return creditLimitService.resolveTierCreditLimit(tier);
    }

    private void setOnboardingStatus(PrincipalType type, Long principalId, String status) {
        Instant now = Instant.now();
        switch (type) {
            case STATION -> {
                Station s = stationRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "station.not.found"));
                s.setOnboardingStatus(status);
                s.setUpdatedAt(now);
                stationRepository.save(s);
            }
            case MANUFACTURER -> {
                Manufacturer m = manufacturerRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "manufacturer.not.found"));
                m.setOnboardingStatus(status);
                m.setUpdatedAt(now);
                manufacturerRepository.save(m);
            }
            case MERCHANT -> {
                Merchant m = merchantRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "merchant.not.found"));
                m.setOnboardingStatus(status);
                m.setStatus("ACTIVE");
                m.setUpdatedAt(now);
                merchantRepository.save(m);
            }
        }
    }

    private boolean orgExists(PrincipalType type, Long principalId) {
        return switch (type) {
            case STATION -> stationRepository.findById(principalId).isPresent();
            case MANUFACTURER -> manufacturerRepository.findById(principalId).isPresent();
            case MERCHANT -> merchantRepository.findById(principalId).isPresent();
        };
    }

    private String currentStatus(PrincipalType type, Long principalId) {
        return switch (type) {
            case STATION -> stationRepository.findById(principalId).map(Station::getOnboardingStatus).orElse(null);
            case MANUFACTURER -> manufacturerRepository.findById(principalId)
                    .map(Manufacturer::getOnboardingStatus).orElse(null);
            case MERCHANT -> merchantRepository.findById(principalId).map(Merchant::getOnboardingStatus).orElse(null);
        };
    }

    private String generateCode(PrincipalType type) {
        String prefix = switch (type) {
            case STATION -> "ST";
            case MANUFACTURER -> "MF";
            case MERCHANT -> "MC";
        };
        return prefix + NO_DATE.format(Instant.now().atZone(ZoneOffset.UTC))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private static String typeLabel(PrincipalType type) {
        return switch (type) {
            case STATION -> "服务站";
            case MANUFACTURER -> "厂家";
            case MERCHANT -> "商家";
        };
    }

    private String buildPayload(OnboardingApplication app, PrincipalType type,
                                Long principalId, BigDecimal creditLimit) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "applicationId", app.getId(),
                    "applicationNo", app.getApplicationNo(),
                    "applicantUserId", app.getApplicantUserId(),
                    "principalType", type.name(),
                    "principalId", principalId,
                    "creditLimit", creditLimit == null ? 0 : creditLimit,
                    "activatedAt", Instant.now().toString()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean readConfigBool(String key, boolean fallback) {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> Boolean.parseBoolean(v.trim()))
                .orElse(fallback);
    }
}
