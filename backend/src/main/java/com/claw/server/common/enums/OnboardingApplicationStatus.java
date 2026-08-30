package com.claw.server.common.enums;

import com.claw.server.common.api.BizException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入驻申请单状态机（增量 C · §3.1）。
 *
 * <p>三类主体（STATION / MANUFACTURER / MERCHANT）共用同一套状态机。
 * 采用<b>手写显式迁移白名单表</b>（11 个状态、20 条迁移），不引 Spring StateMachine ——
 * 状态量级小，引框架是过度设计。非白名单组合一律抛 {@code onboarding.status.illegal}。
 */
public enum OnboardingApplicationStatus {

    DRAFT,
    SUBMITTED,
    REVIEWING,
    APPROVED,
    RETURNED,
    REJECTED,
    PENDING_PAY_CONFIRM,
    DEPOSIT_PAID,
    ACTIVATED,
    EXPIRED,
    CANCELLED;

    /**
     * 迁移白名单（from → 允许的 to 集合）。
     * ACTIVATED / CANCELLED 为终态（无后继）；REJECTED 允许申诉重提回 DRAFT。
     */
    private static final Map<OnboardingApplicationStatus, Set<OnboardingApplicationStatus>> TRANSITIONS = Map.of(
            DRAFT,               Set.of(SUBMITTED, CANCELLED),
            SUBMITTED,           Set.of(REVIEWING, CANCELLED),
            REVIEWING,           Set.of(APPROVED, RETURNED, REJECTED, CANCELLED),
            RETURNED,            Set.of(DRAFT),
            REJECTED,            Set.of(DRAFT),
            APPROVED,            Set.of(PENDING_PAY_CONFIRM, EXPIRED, CANCELLED),
            PENDING_PAY_CONFIRM, Set.of(DEPOSIT_PAID, APPROVED),
            DEPOSIT_PAID,        Set.of(ACTIVATED),
            EXPIRED,             Set.of(APPROVED));

    /** 终态：不可再迁移。 */
    private static final Set<OnboardingApplicationStatus> TERMINAL = Set.of(ACTIVATED, CANCELLED);

    /** 是否允许迁移到目标状态。 */
    public boolean canTransitTo(OnboardingApplicationStatus to) {
        if (to == null) {
            return false;
        }
        Set<OnboardingApplicationStatus> allowed = TRANSITIONS.get(this);
        return allowed != null && allowed.contains(to);
    }

    /**
     * 断言迁移合法，非法则抛业务异常。
     *
     * @param to 目标状态
     * @throws BizException 40940 onboarding.status.illegal
     */
    public void assertTransition(OnboardingApplicationStatus to) {
        if (!canTransitTo(to)) {
            throw BizException.of(40940, "onboarding.status.illegal", this.name(), to == null ? "NULL" : to.name());
        }
    }

    /** 是否终态（不可再迁移）。 */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * 非终态集合：草稿唯一性索引 {@code uq_onb_app_active} 覆盖的状态，
     * 即同一用户 + 同一主体类型同时最多 1 条。
     */
    public static List<String> activeStatusNames() {
        return List.of(DRAFT.name(), SUBMITTED.name(), REVIEWING.name(), APPROVED.name(),
                RETURNED.name(), PENDING_PAY_CONFIRM.name(), DEPOSIT_PAID.name());
    }

    /** 管理页列表状态标签（周老板要求的中文标签）。 */
    public String label() {
        return switch (this) {
            case DRAFT -> "草稿";
            case SUBMITTED -> "已提交";
            case REVIEWING -> "待审";
            case APPROVED -> "待缴保证金";
            case RETURNED -> "退回补正";
            case REJECTED -> "已驳回";
            case PENDING_PAY_CONFIRM -> "待确认到账";
            case DEPOSIT_PAID -> "已付款";
            case ACTIVATED -> "已激活";
            case EXPIRED -> "已超时";
            case CANCELLED -> "已撤回";
        };
    }
}
