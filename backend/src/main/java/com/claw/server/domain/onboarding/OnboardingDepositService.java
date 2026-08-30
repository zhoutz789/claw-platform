package com.claw.server.domain.onboarding;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.OnboardingApplicationStatus;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.domain.role.PrincipalResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 入驻保证金缴款服务（增量 C · O18 / O19 / O27）。
 *
 * <p>⚠️ 资金域纪律（设计 §1.5）：{@code onboarding_deposits} 是<b>业务台账，不是复式记账</b>，
 * 本轮不写任何 ledger / account_entries 分录。若后续要求保证金入账，必须跨域调用
 * {@code AccountService} / {@code LedgerService}，不得直连其 Repository（ArchUnit 会拦）。
 *
 * <p><b>确认到账 → 触发自动激活</b>，但两者分处两个事务：
 * 缴款确认（钱已到账）先提交，激活再跑 {@code REQUIRES_NEW} 独立事务。
 * 激活失败只回滚激活动作，保证金保持 CONFIRMED、申请单停留在 DEPOSIT_PAID，
 * 由管理页「重试激活」人工入口重跑（Q8 采纳，不做静默自动重试）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingDepositService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OnboardingDepositRepository depositRepository;
    private final OnboardingApplicationRepository applicationRepository;
    private final OnboardingApplicationLogService logService;
    private final OnboardingDepositTierRepository tierRepository;
    private final OnboardingActivationService activationService;
    private final PrincipalResolver principalResolver;
    /**
     * 显式事务模板：用于把「缴款确认」与「自动激活」切成两个独立事务。
     * 由 Spring Boot {@code TransactionAutoConfiguration} 自动装配。
     */
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * 申请人上传转账凭证（首期线下转账，Q4）。
     *
     * @param applicationId 申请单 ID（须处于 APPROVED 或 PENDING_PAY_CONFIRM）
     * @param amount        实缴金额（为空时取档位金额）
     * @return 缴款记录
     */
    @Transactional
    public OnboardingDeposit submitVoucher(Long applicationId, BigDecimal amount, String voucherUrl,
                                           String payerName, String payerAccount, String payMethod,
                                           Long userId) {
        OnboardingApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.application.not.found"));
        if (!app.getApplicantUserId().equals(userId)) {
            throw BizException.of(40301, "onboarding.application.not.owner");
        }
        if (app.status() != OnboardingApplicationStatus.APPROVED
                && app.status() != OnboardingApplicationStatus.PENDING_PAY_CONFIRM) {
            throw BizException.of(40940, "onboarding.status.illegal", app.getStatus(), "PENDING_PAY_CONFIRM");
        }
        if (!StringUtils.hasText(voucherUrl)) {
            throw BizException.of(10001, "onboarding.deposit.voucher.required");
        }
        BigDecimal payAmount = amount;
        if (payAmount == null && app.getDepositTierId() != null) {
            payAmount = tierRepository.findById(app.getDepositTierId())
                    .map(OnboardingDepositTier::getDepositAmount).orElse(null);
        }
        if (payAmount == null) {
            throw BizException.of(10001, "onboarding.deposit.amount.required");
        }
        OnboardingDeposit dep = OnboardingDeposit.builder()
                .depositNo(generateDepositNo())
                .applicationId(applicationId)
                .principalType(app.getApplicantType())
                .tierId(app.getDepositTierId())
                .amount(payAmount)
                .payMethod(StringUtils.hasText(payMethod) ? payMethod : "OFFLINE_TRANSFER")
                .voucherUrl(voucherUrl)
                .payerName(payerName)
                .payerAccount(payerAccount)
                .status(OnboardingDeposit.Status.PENDING_CONFIRM.name())
                .build();
        OnboardingDeposit saved = depositRepository.save(dep);
        String from = app.getStatus();
        app.transitTo(OnboardingApplicationStatus.PENDING_PAY_CONFIRM);
        applicationRepository.save(app);
        logService.record(applicationId, from, OnboardingApplicationStatus.PENDING_PAY_CONFIRM.name(),
                OnboardingApplicationLog.Action.PAY_SUBMIT, userId,
                OnboardingApplicationLog.OperatorType.APPLICANT,
                "上传缴款凭证 " + saved.getDepositNo() + "，金额 " + payAmount);
        log.info("入驻保证金缴款凭证已上传 app={} depositNo={} amount={}", applicationId,
                saved.getDepositNo(), payAmount);
        return saved;
    }

    /**
     * 平台 / 财务驳回缴款（重传凭证），申请单回到 APPROVED（待缴保证金）。
     */
    @Transactional
    public OnboardingDeposit reject(Long depositId, String reason, Long operatorId) {
        OnboardingDeposit dep = load(depositId);
        if (!OnboardingDeposit.Status.PENDING_CONFIRM.name().equals(dep.getStatus())) {
            throw BizException.of(40940, "onboarding.deposit.not.pending");
        }
        if (!StringUtils.hasText(reason)) {
            throw BizException.of(10001, "onboarding.deposit.reject.reason.required");
        }
        dep.setStatus(OnboardingDeposit.Status.REJECTED.name());
        dep.setRejectReason(reason);
        dep.setUpdatedAt(Instant.now());
        OnboardingDeposit saved = depositRepository.save(dep);

        OnboardingApplication app = applicationRepository.findById(dep.getApplicationId())
                .orElseThrow(() -> BizException.of(40401, "onboarding.application.not.found"));
        if (app.status() == OnboardingApplicationStatus.PENDING_PAY_CONFIRM) {
            String from = app.getStatus();
            app.transitTo(OnboardingApplicationStatus.APPROVED);
            applicationRepository.save(app);
            logService.record(app.getId(), from, OnboardingApplicationStatus.APPROVED.name(),
                    OnboardingApplicationLog.Action.PAY_REJECT, operatorId,
                    OnboardingApplicationLog.OperatorType.PLATFORM, "缴款凭证驳回：" + reason);
        }
        return saved;
    }

    /**
     * 确认到账 → 申请单转 DEPOSIT_PAID（已付款）→ 触发自动激活。
     *
     * <p><b>事务边界（本方法刻意不标注 @Transactional）</b>：两个动作必须分处两个事务 ——
     * <ol>
     *   <li><b>事务 1</b>（{@code TransactionTemplate}）：缴款确认 + 申请单转 DEPOSIT_PAID，
     *       <b>立即提交</b>。「钱已到账」这一事实不能因为后续激活失败而被回滚；</li>
     *   <li><b>事务 2</b>（{@link OnboardingActivationService#activate} 的 REQUIRES_NEW）：
     *       激活动作，失败只回滚激活本身。</li>
     * </ol>
     *
     * <p>⚠️ 若把两步塞进同一个 @Transactional 方法再用 REQUIRES_NEW 调激活，
     * 内层事务读不到外层尚未提交的 DEPOSIT_PAID（READ_COMMITTED），会误判
     * {@code onboarding.activation.illegal}。这正是此处用 TransactionTemplate 显式分段的原因。
     *
     * @return 激活结果；激活失败时抛异常，但缴款确认已提交（管理页可人工重试激活）
     */
    public ActivationResult confirm(Long depositId, Long operatorId) {
        // —— 事务 1：缴款确认（提交后不可回滚）——
        Long applicationId = transactionTemplate.execute(status -> confirmDepositTx(depositId, operatorId));

        // —— 事务 2：自动激活（独立事务，失败只回滚激活本身）——
        try {
            return activationService.activate(applicationId);
        } catch (Exception e) {
            log.error("入驻自动激活失败 app={} —— 保证金已在独立事务中确认为到账，不回滚；"
                            + "申请单停留在 DEPOSIT_PAID，请在管理页「重试激活」人工处理",
                    applicationId, e);
            throw e;
        }
    }

    /**
     * 缴款确认本体（独立事务，由 {@link #confirm} 经 TransactionTemplate 调用）。
     *
     * @return 所属申请单 ID
     */
    private Long confirmDepositTx(Long depositId, Long operatorId) {
        OnboardingDeposit dep = load(depositId);
        if (!OnboardingDeposit.Status.PENDING_CONFIRM.name().equals(dep.getStatus())) {
            throw BizException.of(40940, "onboarding.deposit.not.pending");
        }
        OnboardingApplication app = applicationRepository.findById(dep.getApplicationId())
                .orElseThrow(() -> BizException.of(40401, "onboarding.application.not.found"));

        dep.setStatus(OnboardingDeposit.Status.CONFIRMED.name());
        dep.setConfirmedBy(operatorId);
        dep.setConfirmedAt(Instant.now());
        dep.setPrincipalType(app.getApplicantType());
        dep.setPrincipalId(app.getPrincipalId());
        dep.setUpdatedAt(Instant.now());
        depositRepository.save(dep);

        String from = app.getStatus();
        app.transitTo(OnboardingApplicationStatus.DEPOSIT_PAID);
        app.setPaidAt(Instant.now());
        applicationRepository.save(app);
        logService.record(app.getId(), from, OnboardingApplicationStatus.DEPOSIT_PAID.name(),
                OnboardingApplicationLog.Action.PAY_CONFIRM, operatorId,
                OnboardingApplicationLog.OperatorType.PLATFORM,
                "保证金到账确认 " + dep.getDepositNo() + "，金额 " + dep.getAmount());

        principalResolver.evict(app.getApplicantUserId());
        log.info("入驻保证金到账确认（已提交）depositNo={} app={} amount={}，开始自动激活",
                dep.getDepositNo(), app.getId(), dep.getAmount());
        return app.getId();
    }

    /** 待确认到账的缴款列表（保证金缴纳确认页）。 */
    @Transactional(readOnly = true)
    public List<OnboardingDeposit> listByStatus(String status) {
        if (StringUtils.hasText(status)) {
            return depositRepository.findByStatusOrderByCreatedAtDesc(status);
        }
        return depositRepository.findAll();
    }

    /** 某申请单的缴款记录。 */
    @Transactional(readOnly = true)
    public List<OnboardingDeposit> listByApplication(Long applicationId) {
        return depositRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId);
    }

    /** 某组织的历史缴款记录（激活后按主体查）。 */
    @Transactional(readOnly = true)
    public List<OnboardingDeposit> listByPrincipal(PrincipalType type, Long principalId) {
        return depositRepository.findByPrincipalTypeAndPrincipalIdOrderByCreatedAtDesc(
                type.name(), principalId);
    }

    @Transactional(readOnly = true)
    public OnboardingDeposit load(Long depositId) {
        return depositRepository.findById(depositId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.deposit.not.found"));
    }

    /** 当前登录用户可看的缴款记录（主账号看本主体，子账号回溯 owner）。 */
    @Transactional(readOnly = true)
    public List<OnboardingDeposit> listMine(Long userId) {
        Optional<PrincipalResolver.PrincipalRef> ref = principalResolver.resolve(userId);
        if (ref.isEmpty()) {
            return List.of();
        }
        return depositRepository.findByPrincipalTypeAndPrincipalIdOrderByCreatedAtDesc(
                ref.get().type().name(), ref.get().principalId());
    }

    private String generateDepositNo() {
        String date = NO_DATE.format(Instant.now().atZone(ZoneOffset.UTC));
        for (int i = 0; i < 10; i++) {
            String no = "DEP" + date + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
            if (depositRepository.findByDepositNo(no).isEmpty()) {
                return no;
            }
        }
        throw new IllegalStateException("无法生成唯一的保证金缴款单号");
    }
}
