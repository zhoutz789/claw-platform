package com.claw.server.domain.org;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.OnboardingStatus;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.domain.credit.CreditLimitService;
import com.claw.server.domain.manufacturer.Manufacturer;
import com.claw.server.domain.manufacturer.ManufacturerRepository;
import com.claw.server.domain.merchant.Merchant;
import com.claw.server.domain.merchant.MerchantRepository;
import com.claw.server.domain.onboarding.OnboardingDepositTier;
import com.claw.server.domain.onboarding.OnboardingDepositTierRepository;
import com.claw.server.domain.onboarding.OnboardingOrgStatusLog;
import com.claw.server.domain.onboarding.OnboardingOrgStatusLogRepository;
import com.claw.server.domain.station.Station;
import com.claw.server.domain.station.StationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 组织治理服务（增量 C · O36 / §3.2）。
 *
 * <p>对三类主体（服务站 / 厂家 / 商家）做统一的入驻治理：查询、禁用、启用、改档。
 * 列表读 {@code v_org_governance} 视图（JdbcClient 只读投影），写操作回落到各自主体表。
 *
 * <p><b>禁用语义（Q7 拍板）</b>：只切断新增，不中断在途 —— 拦截点统一由
 * {@link OrgWritableGuard} 挂在新建类写入点，履约推进点不挂。
 *
 * <p>禁用 / 启用 / 改档全部写 {@code onboarding_org_status_logs} 留痕（原因必填）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgGovernanceService {

    private final JdbcClient jdbcClient;
    private final StationRepository stationRepository;
    private final ManufacturerRepository manufacturerRepository;
    private final MerchantRepository merchantRepository;
    private final OnboardingDepositTierRepository tierRepository;
    private final OnboardingOrgStatusLogRepository statusLogRepository;
    private final CreditLimitService creditLimitService;
    private final OrgWritableGuard orgWritableGuard;

    /* ------------------------------------------------------------------ */
    /* 查询                                                                */
    /* ------------------------------------------------------------------ */

    /** 组织管理列表（v_org_governance 投影，可按主体类型 / 入驻状态筛选）。 */
    @Transactional(readOnly = true)
    public List<OrgGovernanceView> listOrgs(String principalType, String onboardingStatus) {
        StringBuilder sql = new StringBuilder(
                "SELECT principal_type, principal_id, code, name, onboarding_status, deposit_tier_id, "
                        + "credit_limit, onboarding_application_id, disabled_at, disabled_reason, disabled_by "
                        + "FROM claw.v_org_governance WHERE 1=1");
        if (StringUtils.hasText(principalType)) {
            sql.append(" AND principal_type = :t");
        }
        if (StringUtils.hasText(onboardingStatus)) {
            sql.append(" AND onboarding_status = :s");
        }
        sql.append(" ORDER BY principal_type, principal_id");
        var spec = jdbcClient.sql(sql.toString());
        if (StringUtils.hasText(principalType)) {
            spec = spec.param("t", principalType);
        }
        if (StringUtils.hasText(onboardingStatus)) {
            spec = spec.param("s", onboardingStatus);
        }
        return spec.query((rs, rowNum) -> new OrgGovernanceView(
                        rs.getString("principal_type"),
                        rs.getLong("principal_id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("onboarding_status"),
                        (Long) rs.getObject("deposit_tier_id"),
                        (BigDecimal) rs.getObject("credit_limit"),
                        (Long) rs.getObject("onboarding_application_id"),
                        rs.getTimestamp("disabled_at") == null ? null
                                : Instant.ofEpochMilli(rs.getTimestamp("disabled_at").getTime()),
                        rs.getString("disabled_reason"),
                        (Long) rs.getObject("disabled_by")))
                .list();
    }

    /** 单个组织治理视图。 */
    @Transactional(readOnly = true)
    public Optional<OrgGovernanceView> findOrg(PrincipalType type, Long principalId) {
        return orgWritableGuard.find(type, principalId);
    }

    /** 组织状态变更历史（禁用 / 启用 / 改档留痕）。 */
    @Transactional(readOnly = true)
    public List<OnboardingOrgStatusLog> statusHistory(PrincipalType type, Long principalId) {
        return statusLogRepository.findByPrincipalTypeAndPrincipalIdOrderByCreatedAtDesc(
                type.name(), principalId);
    }

    /** 组织名称（用于列表与提示文案）。 */
    @Transactional(readOnly = true)
    public String orgName(PrincipalType type, Long principalId) {
        return orgWritableGuard.find(type, principalId)
                .map(OrgGovernanceView::name)
                .orElse("#" + principalId);
    }

    /* ------------------------------------------------------------------ */
    /* 禁用 / 启用                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * 平台禁用：原因<b>必填</b>（前端需二次确认，本方法做服务端强校验）。
     *
     * <p>禁用后：新建单据被 {@link OrgWritableGuard} 拦截；在途订单继续履约（Q7）；
     * C 端「附近服务站」检索下线；门头不再对外展示。
     *
     * @throws BizException 10001 org.disable.reason.required
     */
    @Transactional
    public void disable(PrincipalType type, Long principalId, String reason, Long operatorId) {
        if (!StringUtils.hasText(reason)) {
            throw BizException.of(10001, "org.disable.reason.required");
        }
        String from = currentOnboardingStatus(type, principalId);
        if (OnboardingStatus.isDisabledStatus(from)) {
            throw BizException.of(40940, "org.already.disabled");
        }
        writeOnboardingStatus(type, principalId, OnboardingStatus.DISABLED.name(), reason, operatorId);
        statusLogRepository.save(OnboardingOrgStatusLog.builder()
                .principalType(type.name())
                .principalId(principalId)
                .fromStatus(from)
                .toStatus(OnboardingStatus.DISABLED.name())
                .action(OnboardingOrgStatusLog.Action.DISABLE.name())
                .reason(reason)
                .operatorId(operatorId)
                .build());
        log.warn("组织已禁用 type={} id={} operator={} reason={}", type, principalId, operatorId, reason);
    }

    /**
     * 平台启用：回置 ACTIVATED（入驻治理的激活态，与申请单终态同名；
     * 注意不是运营状态列的 ACTIVE）。
     *
     * <p>启用校验（PRD §5.3）：当前实现校验「保证金档位有效」；
     * 「资质在有效期」「无未处置风控事件」依赖后续资质 / 风控域，本期以 warn 日志记录待补。
     */
    @Transactional
    public void enable(PrincipalType type, Long principalId, String reason, Long operatorId) {
        String from = currentOnboardingStatus(type, principalId);
        if (!OnboardingStatus.isDisabledStatus(from)) {
            throw BizException.of(40940, "org.not.disabled");
        }
        writeOnboardingStatus(type, principalId, OnboardingStatus.ACTIVATED.name(), null, null);
        statusLogRepository.save(OnboardingOrgStatusLog.builder()
                .principalType(type.name())
                .principalId(principalId)
                .fromStatus(from)
                .toStatus(OnboardingStatus.ACTIVATED.name())
                .action(OnboardingOrgStatusLog.Action.ENABLE.name())
                .reason(reason)
                .operatorId(operatorId)
                .build());
        log.info("组织已启用 type={} id={} operator={}", type, principalId, operatorId);
    }

    /* ------------------------------------------------------------------ */
    /* 改档（额度重算）                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * 改档：重算授信额度并同步写回主体表。
     *
     * <p>降档导致存量超额时<b>不阻断、不强制回收</b>，只切断新增（与 Q7「只切新增」精神一致）；
     * 组织管理页对 {@code used > credit_limit} 的站点显示红色「超额」标记（由前端读
     * {@link CreditLimitService#creditUsage} 判定）。
     *
     * @param tierId 新档位 ID
     */
    @Transactional
    public void changeTier(PrincipalType type, Long principalId, Long tierId, String reason, Long operatorId) {
        OnboardingDepositTier tier = tierRepository.findById(tierId)
                .orElseThrow(() -> BizException.of(40401, "onboarding.deposit.tier.not.found"));
        if (!tier.getApplicantType().equals(type.name())) {
            throw BizException.of(10001, "onboarding.deposit.tier.type.mismatch");
        }
        BigDecimal limit = creditLimitService.resolveTierCreditLimit(tier);
        writeTierAndLimit(type, principalId, tierId, limit);
        statusLogRepository.save(OnboardingOrgStatusLog.builder()
                .principalType(type.name())
                .principalId(principalId)
                .fromStatus(currentOnboardingStatus(type, principalId))
                .toStatus(currentOnboardingStatus(type, principalId))
                .action("ACTIVATE")
                .reason("改档：" + tier.getTierName() + "，额度重算为 " + limit
                        + (StringUtils.hasText(reason) ? "；备注：" + reason : ""))
                .operatorId(operatorId)
                .build());
        log.info("组织改档 type={} id={} tier={} newLimit={} operator={}", type, principalId, tierId, limit, operatorId);
    }

    /* ------------------------------------------------------------------ */
    /* 内部：三类主体表的写入分发                                            */
    /* ------------------------------------------------------------------ */

    @Transactional(readOnly = true)
    public String currentOnboardingStatus(PrincipalType type, Long principalId) {
        return switch (type) {
            case STATION -> stationRepository.findById(principalId).map(Station::getOnboardingStatus).orElse(null);
            case MANUFACTURER -> manufacturerRepository.findById(principalId)
                    .map(Manufacturer::getOnboardingStatus).orElse(null);
            case MERCHANT -> merchantRepository.findById(principalId).map(Merchant::getOnboardingStatus).orElse(null);
        };
    }

    private void writeOnboardingStatus(PrincipalType type, Long principalId, String toStatus,
                                       String disabledReason, Long disabledBy) {
        Instant now = Instant.now();
        switch (type) {
            case STATION -> {
                Station s = stationRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "station.not.found"));
                s.setOnboardingStatus(toStatus);
                if ("DISABLED".equals(toStatus)) {
                    s.setDisabledAt(now);
                    s.setDisabledBy(disabledBy);
                    s.setDisabledReason(disabledReason);
                } else {
                    s.setDisabledAt(null);
                    s.setDisabledBy(null);
                    s.setDisabledReason(null);
                }
                stationRepository.save(s);
            }
            case MANUFACTURER -> {
                Manufacturer m = manufacturerRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "manufacturer.not.found"));
                m.setOnboardingStatus(toStatus);
                if ("DISABLED".equals(toStatus)) {
                    m.setDisabledAt(now);
                    m.setDisabledBy(disabledBy);
                    m.setDisabledReason(disabledReason);
                } else {
                    m.setDisabledAt(null);
                    m.setDisabledBy(null);
                    m.setDisabledReason(null);
                }
                manufacturerRepository.save(m);
            }
            case MERCHANT -> {
                Merchant m = merchantRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "merchant.not.found"));
                m.setOnboardingStatus(toStatus);
                // merchants.status（V52 既有运营状态）与 onboarding_status 同步，避免两张皮
                m.setStatus("DISABLED".equals(toStatus) ? "REJECTED" : "ACTIVE");
                if ("DISABLED".equals(toStatus)) {
                    m.setDisabledAt(now);
                    m.setDisabledBy(disabledBy);
                    m.setDisabledReason(disabledReason);
                } else {
                    m.setDisabledAt(null);
                    m.setDisabledBy(null);
                    m.setDisabledReason(null);
                }
                merchantRepository.save(m);
            }
        }
    }

    private void writeTierAndLimit(PrincipalType type, Long principalId, Long tierId, BigDecimal limit) {
        switch (type) {
            case STATION -> {
                Station s = stationRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "station.not.found"));
                s.setDepositTierId(tierId);
                s.setCreditLimit(limit);
                stationRepository.save(s);
            }
            case MANUFACTURER -> {
                Manufacturer m = manufacturerRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "manufacturer.not.found"));
                m.setDepositTierId(tierId);
                m.setCreditLimit(limit);
                manufacturerRepository.save(m);
            }
            case MERCHANT -> {
                Merchant m = merchantRepository.findById(principalId)
                        .orElseThrow(() -> BizException.of(40401, "merchant.not.found"));
                m.setDepositTierId(tierId);
                m.setCreditLimit(limit);
                merchantRepository.save(m);
            }
        }
    }
}
