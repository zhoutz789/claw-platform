package com.claw.server.domain.subaccount;

import com.claw.server.common.api.BizException;
import com.claw.server.domain.role.Permission;
import com.claw.server.domain.role.PermissionRepository;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.PrincipalResolver;
import com.claw.server.domain.role.RoleTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子账号授权单元测试（增量 C · AC ②③）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code ALL} 模式只存模板码、<b>不落明细</b>，生效集合 = 主账号模板全量 ——
 *       这是「平台新增功能后已授权子账号零改动自动获得」的机制基础；</li>
 *   <li>{@code PARTIAL} 模式的交集防越权（O30）：主账号模板没有的码被剔除并回报；</li>
 *   <li>授权变更后清权限缓存与主体解析缓存（5s 内生效）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubAccountGrantServiceTest {

    @Mock
    private SubAccountGrantRepository grantRepository;
    @Mock
    private SubAccountGrantItemRepository itemRepository;
    @Mock
    private SubAccountRepository subAccountRepository;
    @Mock
    private PermissionRepository permissionRepository;
    @Mock
    private RoleTemplateService roleTemplateService;
    @Mock
    private PermissionService permissionService;
    @Mock
    private PrincipalResolver principalResolver;

    @InjectMocks
    private SubAccountGrantService grantService;

    /** 主账号（服务站）模板权限集合。 */
    private static final Set<String> OWNER_PERMS = Set.of(
            "menu:onboarding", "menu:sub-accounts", "org:subaccount:manage", "org:credit:view");

    private SubAccount subAccount(Long id) {
        return SubAccount.builder()
                .id(id).ownerPrincipalType("STATION").ownerPrincipalId(12L)
                .userId(555L).status("ACTIVE").build();
    }

    private SubAccountGrant grant(Long id, String mode) {
        return SubAccountGrant.builder()
                .id(id).subAccountId(1L).grantMode(mode)
                .templateCode("ALL".equals(mode) ? "STATION" : null)
                .status("ACTIVE").build();
    }

    // ---------- ALL 模式 ----------

    @Test
    void grantAll_expandsTemplateWithoutPersistingItems() {
        when(subAccountRepository.findById(1L)).thenReturn(Optional.of(subAccount(1L)));
        when(roleTemplateService.expandPermissions("STATION")).thenReturn(OWNER_PERMS);
        when(grantRepository.findTopBySubAccountIdOrderByIdDesc(1L)).thenReturn(Optional.empty());
        when(grantRepository.save(any(SubAccountGrant.class))).thenReturn(grant(9L, "ALL"));

        GrantResult r = grantService.grant(1L, "ALL", null, 1L);

        assertEquals("ALL", r.grantMode());
        assertEquals(OWNER_PERMS, r.effectivePerms());
        assertEquals(0, r.removedCount());
        assertEquals("STATION", r.templateCode());
        // 关键：ALL 模式不落明细，未来新功能挂到模板后自动继承
        verify(itemRepository, never()).save(any(SubAccountGrantItem.class));
        verify(permissionService).evictUser(555L);
    }

    // ---------- PARTIAL 模式：交集防越权 ----------

    @Test
    void grantPartial_intersectsWithOwnerTemplate() {
        when(subAccountRepository.findById(1L)).thenReturn(Optional.of(subAccount(1L)));
        when(roleTemplateService.expandPermissions("STATION")).thenReturn(OWNER_PERMS);
        when(grantRepository.findTopBySubAccountIdOrderByIdDesc(1L)).thenReturn(Optional.empty());
        when(grantRepository.save(any(SubAccountGrant.class))).thenReturn(grant(9L, "PARTIAL"));
        // 三项都存在于权限目录，但 org:status:manage 不在主账号模板里 → 应被剔除
        when(permissionRepository.findByCode(anyString()))
                .thenAnswer(inv -> Optional.of(Permission.builder()
                        .code(inv.getArgument(0)).name(inv.getArgument(0)).build()));
        when(itemRepository.findByGrantId(9L)).thenReturn(List.of());

        GrantResult r = grantService.grant(1L, "PARTIAL",
                List.of("menu:sub-accounts", "org:credit:view", "org:status:manage"), 1L);

        assertTrue(r.hasRemoved());
        assertEquals(1, r.removedCount());
        assertEquals(List.of("org:status:manage"), r.removedPerms(), "越权项应被剔除");
        assertEquals(Set.of("menu:sub-accounts", "org:credit:view"), r.effectivePerms());
        // 只落合法的两项明细
        verify(itemRepository, org.mockito.Mockito.times(2)).save(any(SubAccountGrantItem.class));
        verify(itemRepository, never()).save(org.mockito.ArgumentMatchers
                .argThat(i -> "org:status:manage".equals(i.getPermissionCode())));
    }

    @Test
    void grantPartial_requiresItems() {
        when(subAccountRepository.findById(1L)).thenReturn(Optional.of(subAccount(1L)));
        when(roleTemplateService.expandPermissions("STATION")).thenReturn(OWNER_PERMS);
        assertThrows(BizException.class, () -> grantService.grant(1L, "PARTIAL", List.of(), 1L));
    }

    @Test
    void grantPartial_rejectsUnknownPermissionCode() {
        when(subAccountRepository.findById(1L)).thenReturn(Optional.of(subAccount(1L)));
        when(roleTemplateService.expandPermissions("STATION")).thenReturn(OWNER_PERMS);
        when(grantRepository.findTopBySubAccountIdOrderByIdDesc(1L)).thenReturn(Optional.empty());
        when(grantRepository.save(any(SubAccountGrant.class))).thenReturn(grant(9L, "PARTIAL"));
        when(permissionRepository.findByCode("not:a:code")).thenReturn(Optional.empty());

        assertThrows(BizException.class,
                () -> grantService.grant(1L, "PARTIAL", List.of("not:a:code"), 1L));
    }

    // ---------- 模式校验 ----------

    @Test
    void grant_rejectsInvalidMode() {
        when(subAccountRepository.findById(1L)).thenReturn(Optional.of(subAccount(1L)));
        assertThrows(BizException.class, () -> grantService.grant(1L, "SOMETHING", null, 1L));
        assertThrows(BizException.class, () -> grantService.grant(1L, null, null, 1L));
    }

    // ---------- 生效集合计算 ----------

    @Test
    void effectivePermissions_allModeReturnsTemplate() {
        when(subAccountRepository.findById(1L)).thenReturn(Optional.of(subAccount(1L)));
        when(grantRepository.findBySubAccountIdAndStatus(1L, "ACTIVE"))
                .thenReturn(Optional.of(grant(9L, "ALL")));
        when(roleTemplateService.expandPermissions("STATION")).thenReturn(OWNER_PERMS);

        assertEquals(OWNER_PERMS, grantService.effectivePermissions(1L));
    }

    @Test
    void effectivePermissions_partialModeIntersects() {
        when(subAccountRepository.findById(1L)).thenReturn(Optional.of(subAccount(1L)));
        when(grantRepository.findBySubAccountIdAndStatus(1L, "ACTIVE"))
                .thenReturn(Optional.of(grant(9L, "PARTIAL")));
        when(roleTemplateService.expandPermissions("STATION")).thenReturn(OWNER_PERMS);
        when(itemRepository.findByGrantId(9L)).thenReturn(List.of(
                SubAccountGrantItem.builder().grantId(9L).permissionCode("menu:sub-accounts").build(),
                SubAccountGrantItem.builder().grantId(9L).permissionCode("org:status:manage").build()));

        Set<String> eff = grantService.effectivePermissions(1L);
        assertEquals(Set.of("menu:sub-accounts"), eff);
        assertFalse(eff.contains("org:status:manage"), "交集应剔除主账号模板没有的码");
    }

    @Test
    void effectivePermissions_emptyWhenNoGrant() {
        when(subAccountRepository.findById(1L)).thenReturn(Optional.of(subAccount(1L)));
        when(roleTemplateService.expandPermissions("STATION")).thenReturn(OWNER_PERMS);
        when(grantRepository.findBySubAccountIdAndStatus(1L, "ACTIVE")).thenReturn(Optional.empty());
        assertTrue(grantService.effectivePermissions(1L).isEmpty());
    }

    @Test
    void effectivePermissionsByUser_emptyForNonSubAccount() {
        when(subAccountRepository.findTopByUserIdAndStatus(anyLong(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        assertTrue(grantService.effectivePermissionsByUser(777L).isEmpty());
    }

    // ---------- 撤销 ----------

    @Test
    void revoke_marksGrantRevokedAndClearsItems() {
        when(subAccountRepository.findById(1L)).thenReturn(Optional.of(subAccount(1L)));
        when(grantRepository.findBySubAccountIdAndStatus(1L, "ACTIVE"))
                .thenReturn(Optional.of(grant(9L, "PARTIAL")));

        grantService.revoke(1L, 1L);

        verify(grantRepository).save(org.mockito.ArgumentMatchers
                .argThat(g -> "REVOKED".equals(g.getStatus())));
        verify(itemRepository).deleteByGrantId(9L);
        verify(permissionService).evictUser(555L);
    }
}
