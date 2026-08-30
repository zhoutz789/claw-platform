package com.claw.server.domain.org;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.domain.role.PrincipalResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 禁用态写入守卫（增量 C · §3.3 / Q7 拍板）。
 *
 * <p><b>语义：只切新增，不中断在途。</b>统一守卫 {@link #assertWritable} 挂在「新建类」写入点，
 * 履约推进点（发货 / 收货 / 取货 / 结算 / 回收）<b>一律不挂</b>，保证在途订单履约到底。
 *
 * <p>子账号同样受限：经 {@link PrincipalResolver} 回溯到 owner 主体，
 * 子账号自动继承主账号的禁用态。
 *
 * <p>读取 {@code v_org_governance} 用 {@code JdbcClient} 只读投影（不做 JPA 实体）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgWritableGuard {

    private final JdbcClient jdbcClient;
    private final PrincipalResolver principalResolver;

    /**
     * 断言指定主体可写（未被平台禁用）。
     *
     * @param type        主体类型
     * @param principalId 主体 ID
     * @throws BizException 40340 org.disabled.readonly（禁用后仅可履约、不可下单）
     */
    @Transactional(readOnly = true)
    public void assertWritable(PrincipalType type, Long principalId) {
        if (type == null || principalId == null) {
            return;
        }
        if (!isWritable(type, principalId)) {
            throw BizException.of(40340, "org.disabled.readonly");
        }
    }

    /** 批量重载：任一被禁用即拒绝。 */
    @Transactional(readOnly = true)
    public void assertWritable(PrincipalType type, List<Long> principalIds) {
        if (type == null || principalIds == null) {
            return;
        }
        for (Long id : principalIds) {
            assertWritable(type, id);
        }
    }

    /** 当前登录账号所属主体可写（子账号自动回溯 owner）。 */
    @Transactional(readOnly = true)
    public void assertCurrentWritable() {
        principalResolver.resolveCurrent()
                .ifPresent(ref -> assertWritable(ref.type(), ref.principalId()));
    }

    /** 判断是否可写（不抛异常，供前端置灰 / 预检使用）。 */
    @Transactional(readOnly = true)
    public boolean isWritable(PrincipalType type, Long principalId) {
        return find(type, principalId).map(OrgGovernanceView::isWritable).orElse(true);
    }

    /** 取组织当前入驻状态（ACTIVATED 申请单在列表上展示「已激活 / 已禁用」用）。 */
    @Transactional(readOnly = true)
    public String orgOnboardingStatus(String principalType, Long principalId) {
        if (principalType == null || principalId == null) {
            return null;
        }
        try {
            return find(PrincipalType.of(principalType), principalId)
                    .map(OrgGovernanceView::onboardingStatus)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 入驻申请提交守卫（§3.3）：已禁用主体不可再申请同类。
     *
     * <p>申请人当前账号若已绑定同类型的组织且该组织处于 DISABLED，拒绝再次申请入驻。
     *
     * @param userId        申请人
     * @param applicantType 申请主体类型
     * @throws BizException 40340 org.disabled.readonly
     */
    @Transactional(readOnly = true)
    public void assertApplicantCanApply(Long userId, String applicantType) {
        Optional<PrincipalResolver.PrincipalRef> ref =
                principalResolver.resolveByType(userId, PrincipalType.of(applicantType));
        if (ref.isPresent() && !isWritable(ref.get().type(), ref.get().principalId())) {
            throw BizException.of(40340, "org.disabled.readonly");
        }
    }

    /** 从视图读一行（三类主体 UNION；视图已过滤 deleted）。 */
    @Transactional(readOnly = true)
    public Optional<OrgGovernanceView> find(PrincipalType type, Long principalId) {
        if (type == null || principalId == null) {
            return Optional.empty();
        }
        return jdbcClient.sql(
                        "SELECT principal_type, principal_id, code, name, onboarding_status, deposit_tier_id, "
                                + "credit_limit, onboarding_application_id, disabled_at, disabled_reason, disabled_by "
                                + "FROM claw.v_org_governance WHERE principal_type = :t AND principal_id = :id")
                .param("t", type.name())
                .param("id", principalId)
                .query((rs, rowNum) -> new OrgGovernanceView(
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
                .optional();
    }
}
