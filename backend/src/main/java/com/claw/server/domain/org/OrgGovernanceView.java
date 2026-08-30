package com.claw.server.domain.org;

import com.claw.server.common.enums.OnboardingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 组织治理视图投影（增量 C · §2.3(16)）。
 *
 * <p>对应数据库视图 {@code v_org_governance}（stations / manufacturers / merchants 三类主体 UNION）。
 * <b>不做 JPA 实体</b>：三张表 UNION 后无单一物理主键，做实体会踩 {@code @Id} 坑；
 * 统一用 Spring {@code JdbcClient} + RowMapper 只读投影。
 *
 * @param principalType            STATION / MANUFACTURER / MERCHANT
 * @param principalId              主体 ID
 * @param code                     组织编码
 * @param name                     组织名称
 * @param onboardingStatus         PENDING / ACTIVATED / DISABLED / REJECTED（见 {@link OnboardingStatus}）
 * @param depositTierId            保证金档位 ID
 * @param creditLimit              授信额度（寄售设备名义货值上限），NULL = 不校验
 * @param onboardingApplicationId  来源入驻申请单
 * @param disabledAt               禁用时点
 * @param disabledReason           禁用原因
 * @param disabledBy               禁用人
 */
public record OrgGovernanceView(
        String principalType,
        Long principalId,
        String code,
        String name,
        String onboardingStatus,
        Long depositTierId,
        BigDecimal creditLimit,
        Long onboardingApplicationId,
        Instant disabledAt,
        String disabledReason,
        Long disabledBy) {

    /** 是否被平台禁用（只切断新增，在途继续履约，Q7）。 */
    public boolean isDisabled() {
        return OnboardingStatus.isDisabledStatus(onboardingStatus);
    }

    /**
     * 是否能产生新单。
     *
     * <p>激活态是 {@code ACTIVATED}（与入驻申请单终态同名），不是 {@code ACTIVE} ——
     * {@code ACTIVE} 是三张主体表运营状态列 {@code status} 的取值，二者不可混用。
     */
    public boolean isWritable() {
        return OnboardingStatus.isWritableStatus(onboardingStatus);
    }
}
