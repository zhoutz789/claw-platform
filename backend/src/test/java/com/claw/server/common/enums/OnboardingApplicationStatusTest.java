package com.claw.server.common.enums;

import com.claw.server.common.api.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入驻申请单状态机单元测试（增量 C · §3.1 AC ③）。
 *
 * <p>覆盖：白名单内的迁移放行、白名单外的一律抛 {@code onboarding.status.illegal}、
 * 终态不可再迁移、周老板要求的管理页中文标签。
 */
class OnboardingApplicationStatusTest {

    @Test
    void legalTransitions_allowed() {
        assertTrue(OnboardingApplicationStatus.DRAFT.canTransitTo(OnboardingApplicationStatus.SUBMITTED));
        assertTrue(OnboardingApplicationStatus.SUBMITTED.canTransitTo(OnboardingApplicationStatus.REVIEWING));
        assertTrue(OnboardingApplicationStatus.REVIEWING.canTransitTo(OnboardingApplicationStatus.APPROVED));
        assertTrue(OnboardingApplicationStatus.REVIEWING.canTransitTo(OnboardingApplicationStatus.RETURNED));
        assertTrue(OnboardingApplicationStatus.REVIEWING.canTransitTo(OnboardingApplicationStatus.REJECTED));
        assertTrue(OnboardingApplicationStatus.APPROVED.canTransitTo(OnboardingApplicationStatus.PENDING_PAY_CONFIRM));
        assertTrue(OnboardingApplicationStatus.PENDING_PAY_CONFIRM
                .canTransitTo(OnboardingApplicationStatus.DEPOSIT_PAID));
        // 财务驳回凭证 → 回到待缴保证金（重传）
        assertTrue(OnboardingApplicationStatus.PENDING_PAY_CONFIRM.canTransitTo(OnboardingApplicationStatus.APPROVED));
        assertTrue(OnboardingApplicationStatus.DEPOSIT_PAID.canTransitTo(OnboardingApplicationStatus.ACTIVATED));
        // 缴款超时后重新激活
        assertTrue(OnboardingApplicationStatus.EXPIRED.canTransitTo(OnboardingApplicationStatus.APPROVED));
    }

    @Test
    void illegalTransitions_rejected() {
        // 草稿不能直接跳到已激活
        assertFalse(OnboardingApplicationStatus.DRAFT.canTransitTo(OnboardingApplicationStatus.ACTIVATED));
        // 未审核不能缴款
        assertFalse(OnboardingApplicationStatus.DRAFT.canTransitTo(OnboardingApplicationStatus.PENDING_PAY_CONFIRM));
        // 已提交不能直接通过（必须先经 REVIEWING 受理）
        assertFalse(OnboardingApplicationStatus.SUBMITTED.canTransitTo(OnboardingApplicationStatus.APPROVED));
        // 终态不可再迁移
        assertFalse(OnboardingApplicationStatus.ACTIVATED.canTransitTo(OnboardingApplicationStatus.DRAFT));
        assertFalse(OnboardingApplicationStatus.CANCELLED.canTransitTo(OnboardingApplicationStatus.DRAFT));
        assertFalse(OnboardingApplicationStatus.DEPOSIT_PAID.canTransitTo(OnboardingApplicationStatus.REVIEWING));
        // null 目标
        assertFalse(OnboardingApplicationStatus.DRAFT.canTransitTo(null));
    }

    @Test
    void assertTransition_throwsWithIllegalCode() {
        BizException ex = assertThrows(BizException.class,
                () -> OnboardingApplicationStatus.DRAFT.assertTransition(OnboardingApplicationStatus.ACTIVATED));
        assertEquals(40940, ex.getCode());
        assertEquals("onboarding.status.illegal", ex.getMessageCode());
    }

    @Test
    void terminalStates() {
        assertTrue(OnboardingApplicationStatus.ACTIVATED.isTerminal());
        assertTrue(OnboardingApplicationStatus.CANCELLED.isTerminal());
        assertFalse(OnboardingApplicationStatus.REJECTED.isTerminal());
        assertFalse(OnboardingApplicationStatus.RETURNED.isTerminal());
    }

    /** 管理页状态标签（周老板点名的「待审 / 待缴保证金 / 已付款 / 已激活 / 已驳回」）。 */
    @Test
    void labels_matchProductRequirement() {
        assertEquals("待审", OnboardingApplicationStatus.REVIEWING.label());
        assertEquals("待缴保证金", OnboardingApplicationStatus.APPROVED.label());
        assertEquals("已付款", OnboardingApplicationStatus.DEPOSIT_PAID.label());
        assertEquals("已激活", OnboardingApplicationStatus.ACTIVATED.label());
        assertEquals("已驳回", OnboardingApplicationStatus.REJECTED.label());
        assertEquals("退回补正", OnboardingApplicationStatus.RETURNED.label());
    }

    /** 草稿唯一性索引覆盖的状态（ACTIVATED 不在其内 —— 激活后允许再开新店）。 */
    @Test
    void activeStatusNames_excludeActivated() {
        var names = OnboardingApplicationStatus.activeStatusNames();
        assertTrue(names.contains("DRAFT"));
        assertTrue(names.contains("DEPOSIT_PAID"));
        assertFalse(names.contains("ACTIVATED"), "激活后应允许同一用户为同一主体类型再次申请");
        assertFalse(names.contains("CANCELLED"));
        assertFalse(names.contains("EXPIRED"));
    }
}
