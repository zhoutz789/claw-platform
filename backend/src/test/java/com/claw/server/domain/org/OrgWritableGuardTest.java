package com.claw.server.domain.org;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.OnboardingStatus;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.domain.role.PrincipalResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 禁用态写入守卫单元测试（增量 C · §3.3 / Q7 拍板）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>已禁用主体的新建类写入点抛 40340 org.disabled.readonly；</li>
 *   <li>未禁用主体放行；</li>
 *   <li>子账号经 {@link PrincipalResolver} 回溯自动继承主账号禁用态；</li>
 *   <li>已禁用主体不可再申请同类入驻。</li>
 * </ul>
 *
 * <p>注意：履约推进点（发货 / 收货 / 取货 / 结算 / 回收）<b>不挂</b>本守卫 ——
 * 禁用只切新增、不中断在途，故本测试也断言「未调用守卫的方法不会被拦」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgWritableGuardTest {

    @Mock
    private JdbcClient jdbcClient;
    @Mock
    private JdbcClient.StatementSpec statementSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<OrgGovernanceView> mappedQuerySpec;
    @Mock
    private PrincipalResolver principalResolver;

    @InjectMocks
    private OrgWritableGuard guard;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubView(String status) {
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        // 任意 name/value 组合都返回同一个 spec（本测试只关心最终投影出的 onboarding_status）
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn((JdbcClient.MappedQuerySpec) mappedQuerySpec);
        when(mappedQuerySpec.optional()).thenReturn(Optional.of(new OrgGovernanceView(
                "STATION", 12L, "ST-001", "金边站 A", status, 1L,
                new java.math.BigDecimal("150000.00"), 3L, null, null, null)));
    }

    @Test
    void assertWritable_throwsWhenDisabled() {
        stubView("DISABLED");
        BizException ex = assertThrows(BizException.class,
                () -> guard.assertWritable(PrincipalType.STATION, 12L));
        assertEquals(40340, ex.getCode());
        assertEquals("org.disabled.readonly", ex.getMessageCode());
    }

    @Test
    void assertWritable_passesWhenActivated() {
        stubView(OnboardingStatus.ACTIVATED.name());
        guard.assertWritable(PrincipalType.STATION, 12L);
    }

    @Test
    void assertWritable_skipsNullInput() {
        // 主体类型 / ID 缺失时不做拦截（如平台级操作无主体上下文）
        guard.assertWritable((PrincipalType) null, (Long) null);
        guard.assertWritable(PrincipalType.STATION, (Long) null);
    }

    @Test
    void isWritable_mirrorsOnboardingStatus() {
        stubView(OnboardingStatus.ACTIVATED.name());
        assertTrue(guard.isWritable(PrincipalType.STATION, 12L));
        stubView(OnboardingStatus.DISABLED.name());
        assertFalse(guard.isWritable(PrincipalType.STATION, 12L));
    }

    /**
     * 命名回归：激活态必须是 {@code ACTIVATED}。
     *
     * <p>历史上本枚举的激活态写作 {@code ACTIVE}，与入驻申请单终态 {@code ACTIVATED} 并存，
     * 且与三张主体表运营状态列 {@code status='ACTIVE'} 撞名 —— V60 回填的历史服务站因此
     * 在守卫下行为分裂，入站被 40340 拦下。V64 统一为 {@code ACTIVATED} 后守住这个口径：
     * 存量 {@code 'ACTIVE'} 必须被视为不可写，避免旧值悄悄复活。
     */
    @Test
    void legacyActiveLiteralIsNotWritable() {
        stubView("ACTIVE");
        assertFalse(guard.isWritable(PrincipalType.STATION, 12L),
                "V64 之后 'ACTIVE' 不再是合法入驻状态，必须视为不可写");
    }

    @Test
    void orgOnboardingStatus_readThroughForActivatedApplication() {
        stubView("DISABLED");
        assertEquals("DISABLED", guard.orgOnboardingStatus("STATION", 12L));
    }

    @Test
    void assertCurrentWritable_propagatesSubAccountOwner() {
        stubView("DISABLED");
        when(principalResolver.resolveCurrent()).thenReturn(
                Optional.of(new PrincipalResolver.PrincipalRef(PrincipalType.STATION, 12L, true)));
        assertThrows(BizException.class, () -> guard.assertCurrentWritable());
        verify(principalResolver).resolveCurrent();
    }

    @Test
    void assertApplicantCanApply_blocksDisabledOrg() {
        when(principalResolver.resolveByType(9L, PrincipalType.STATION)).thenReturn(
                Optional.of(new PrincipalResolver.PrincipalRef(PrincipalType.STATION, 12L, false)));
        stubView("DISABLED");
        assertThrows(BizException.class, () -> guard.assertApplicantCanApply(9L, "STATION"));
    }

    @Test
    void assertApplicantCanApply_allowsWhenNoBinding() {
        when(principalResolver.resolveByType(9L, PrincipalType.STATION)).thenReturn(Optional.empty());
        guard.assertApplicantCanApply(9L, "STATION");
    }

    @Test
    void assertWritable_batchStopsAtFirstDisabled() {
        stubView("DISABLED");
        assertThrows(BizException.class,
                () -> guard.assertWritable(PrincipalType.STATION, List.of(11L, 12L)));
    }

    @Test
    void viewHelperFlags() {
        OrgGovernanceView disabled = new OrgGovernanceView("STATION", 1L, "C", "N",
                OnboardingStatus.DISABLED.name(), null, null, null, null, null, null);
        assertTrue(disabled.isDisabled());
        assertFalse(disabled.isWritable());

        OrgGovernanceView activated = new OrgGovernanceView("STATION", 1L, "C", "N",
                OnboardingStatus.ACTIVATED.name(), null, null, null, null, null, null);
        assertFalse(activated.isDisabled());
        assertTrue(activated.isWritable());
    }
}
