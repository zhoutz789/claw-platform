package com.claw.server.domain.inventory;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.ClawUser;
import com.claw.server.domain.role.PermissionService;
import com.claw.server.domain.role.PrincipalResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元验证：{@link InventoryScopeService#resolveCurrent(Long, Long)} 的作用域解析、越权校验、
 * 下属服务站动态反查（模块三 · M3-1/2/3）。
 *
 * <p>纯 Mockito 单测，不依赖数据库：mock {@code PrincipalResolver} / {@code PermissionService} /
 * {@code InventoryRepository}，直接驱动作用域计算逻辑。登录态（{@link AuthContext}）通过
 * {@link SecurityContextHolder} 手动注入。
 */
@ExtendWith(MockitoExtension.class)
class InventoryScopeServiceTest {

    private static final long MFG_ID = 12L;
    private static final long STATION_ID = 7L;
    private static final long PA_USER = 99L;
    private static final long MFG_USER = 12L;
    private static final long STA_USER = 7L;

    @Mock
    private PrincipalResolver principalResolver;
    @Mock
    private PermissionService permissionService;
    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private InventoryScopeService scopeService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(long userId) {
        ClawUser user = new ClawUser(userId, "1380000" + userId, "MFG");
        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("MANUFACTURER：current=OWNED_BY_MFG，下属站点=动态反查的 CONSIGNED 站点集合")
    void manufacturerScope_usesOwnedByMfgAndDynamicSubs() {
        loginAs(MFG_USER);
        when(principalResolver.resolveAll(MFG_USER))
                .thenReturn(List.of(new PrincipalResolver.PrincipalRef(PrincipalType.MANUFACTURER, MFG_ID, false)));
        when(permissionService.isPlatformAdmin(MFG_USER)).thenReturn(false);
        when(inventoryRepository.findDistinctHolderStationIdsByManufacturer(MFG_ID, OwnershipType.CONSIGNED))
                .thenReturn(List.of(3L, 7L, 9L));

        InventoryScope.ScopeInfo info = scopeService.resolveCurrent(null, null);

        assertEquals(InventoryScope.ScopeLevel.MANUFACTURER, info.scopeLevel());
        assertEquals(MFG_ID, info.effectiveManufacturerId());
        assertEquals(null, info.effectiveStationId());
        assertTrue(info.subordinateStationIds().containsAll(List.of(3L, 7L, 9L)));
        assertEquals(3, info.subordinateStationIds().size());
        // 下属站点反查必须以 CONSIGNED 为口径，且传入的是自身厂家 ID
        verify(inventoryRepository).findDistinctHolderStationIdsByManufacturer(eq(MFG_ID), eq(OwnershipType.CONSIGNED));
    }

    @Test
    @DisplayName("STATION：current=本站在库，subordinateStationIds 为空")
    void stationScope_emptySubs() {
        loginAs(STA_USER);
        when(principalResolver.resolveAll(STA_USER))
                .thenReturn(List.of(new PrincipalResolver.PrincipalRef(PrincipalType.STATION, STATION_ID, false)));
        when(permissionService.isPlatformAdmin(STA_USER)).thenReturn(false);

        InventoryScope.ScopeInfo info = scopeService.resolveCurrent(null, null);

        assertEquals(InventoryScope.ScopeLevel.STATION, info.scopeLevel());
        assertEquals(STATION_ID, info.effectiveStationId());
        assertTrue(info.subordinateStationIds().isEmpty());
    }

    @Test
    @DisplayName("PLATFORM_ADMIN：全平台，platformAdmin=true，principalType 标记为 MANUFACTURER")
    void platformAdminScope_fullPlatform() {
        loginAs(PA_USER);
        when(permissionService.isPlatformAdmin(PA_USER)).thenReturn(true);

        InventoryScope.ScopeInfo info = scopeService.resolveCurrent(null, null);

        assertEquals(InventoryScope.ScopeLevel.PLATFORM, info.scopeLevel());
        assertTrue(info.platformAdmin());
        assertEquals(null, info.effectiveManufacturerId());
        assertEquals(null, info.effectiveStationId());
        assertTrue(info.subordinateStationIds().isEmpty());
    }

    @Test
    @DisplayName("未绑定主体（有账号但无 principal_binding）：scopeLevel=NONE，不抛异常")
    void noneScope_noBinding() {
        loginAs(12345L);
        when(principalResolver.resolveAll(12345L)).thenReturn(List.of());
        when(permissionService.isPlatformAdmin(12345L)).thenReturn(false);

        InventoryScope.ScopeInfo info = scopeService.resolveCurrent(null, null);

        assertEquals(InventoryScope.ScopeLevel.NONE, info.scopeLevel());
        assertEquals(null, info.principalType());
        assertTrue(info.subordinateStationIds().isEmpty());
    }

    @Test
    @DisplayName("未登录（userId=null）：scopeLevel=NONE，不抛异常，且不触发权限查询")
    void noneScope_nullUser() {
        // 未注入任何 Authentication → AuthContext.currentUserId() == null
        InventoryScope.ScopeInfo info = scopeService.resolveCurrent(null, null);

        assertEquals(InventoryScope.ScopeLevel.NONE, info.scopeLevel());
        assertTrue(info.subordinateStationIds().isEmpty());
    }

    @Test
    @DisplayName("越权覆盖②：MANUFACTURER 用他人 manufacturerId → BizException 40301")
    void overrideManufacturerWrongMfg_rejected() {
        loginAs(MFG_USER);
        when(principalResolver.resolveAll(MFG_USER))
                .thenReturn(List.of(new PrincipalResolver.PrincipalRef(PrincipalType.MANUFACTURER, MFG_ID, false)));
        when(permissionService.isPlatformAdmin(MFG_USER)).thenReturn(false);
        when(inventoryRepository.findDistinctHolderStationIdsByManufacturer(MFG_ID, OwnershipType.CONSIGNED))
                .thenReturn(List.of(3L, 7L));

        BizException ex = assertThrows(BizException.class,
                () -> scopeService.resolveCurrent(99999L, null));
        assertEquals(40301, ex.getCode());
        assertEquals("inventory.scope.forbidden", ex.getMessageCode());
    }

    @Test
    @DisplayName("越权覆盖②：MANUFACTURER 用非下属 stationId → BizException 40301")
    void overrideManufacturerWrongStation_rejected() {
        loginAs(MFG_USER);
        when(principalResolver.resolveAll(MFG_USER))
                .thenReturn(List.of(new PrincipalResolver.PrincipalRef(PrincipalType.MANUFACTURER, MFG_ID, false)));
        when(permissionService.isPlatformAdmin(MFG_USER)).thenReturn(false);
        when(inventoryRepository.findDistinctHolderStationIdsByManufacturer(MFG_ID, OwnershipType.CONSIGNED))
                .thenReturn(List.of(3L, 7L));

        BizException ex = assertThrows(BizException.class,
                () -> scopeService.resolveCurrent(MFG_ID, 88888L));
        assertEquals(40301, ex.getCode());
    }

    @Test
    @DisplayName("越权覆盖②：STATION 用非自身 stationId → BizException 40301")
    void overrideStationWrongStation_rejected() {
        loginAs(STA_USER);
        when(principalResolver.resolveAll(STA_USER))
                .thenReturn(List.of(new PrincipalResolver.PrincipalRef(PrincipalType.STATION, STATION_ID, false)));
        when(permissionService.isPlatformAdmin(STA_USER)).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> scopeService.resolveCurrent(null, 88888L));
        assertEquals(40301, ex.getCode());
    }

    @Test
    @DisplayName("越权覆盖②：PLATFORM_ADMIN 任意覆盖值均放行（不抛异常）")
    void overridePlatformAnyValue_allowed() {
        loginAs(PA_USER);
        when(permissionService.isPlatformAdmin(PA_USER)).thenReturn(true);

        InventoryScope.ScopeInfo info = scopeService.resolveCurrent(99999L, 88888L);

        assertEquals(InventoryScope.ScopeLevel.PLATFORM, info.scopeLevel());
        assertTrue(info.platformAdmin());
    }

    @Test
    @DisplayName("越权覆盖：MANUFACTURER 用自身 manufacturerId 收窄被放行")
    void overrideManufacturerOwnId_allowed() {
        loginAs(MFG_USER);
        when(principalResolver.resolveAll(MFG_USER))
                .thenReturn(List.of(new PrincipalResolver.PrincipalRef(PrincipalType.MANUFACTURER, MFG_ID, false)));
        when(permissionService.isPlatformAdmin(MFG_USER)).thenReturn(false);
        when(inventoryRepository.findDistinctHolderStationIdsByManufacturer(MFG_ID, OwnershipType.CONSIGNED))
                .thenReturn(List.of(3L, 7L));

        InventoryScope.ScopeInfo info = scopeService.resolveCurrent(MFG_ID, null);

        assertEquals(InventoryScope.ScopeLevel.MANUFACTURER, info.scopeLevel());
        assertEquals(MFG_ID, info.effectiveManufacturerId());
    }

    @Test
    @DisplayName("MANUFACTURER 无任何寄售在站：subordinateStationIds 为空（不依赖新表）")
    void manufacturerNoConsigned_emptySubs() {
        loginAs(MFG_USER);
        when(principalResolver.resolveAll(MFG_USER))
                .thenReturn(List.of(new PrincipalResolver.PrincipalRef(PrincipalType.MANUFACTURER, MFG_ID, false)));
        when(permissionService.isPlatformAdmin(MFG_USER)).thenReturn(false);
        when(inventoryRepository.findDistinctHolderStationIdsByManufacturer(MFG_ID, OwnershipType.CONSIGNED))
                .thenReturn(List.of());

        InventoryScope.ScopeInfo info = scopeService.resolveCurrent(null, null);

        assertTrue(info.subordinateStationIds().isEmpty());
    }
}
