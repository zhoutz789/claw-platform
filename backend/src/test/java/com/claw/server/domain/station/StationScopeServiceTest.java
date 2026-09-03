package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.enums.PrincipalType;
import com.claw.server.domain.inventory.InventoryScope;
import com.claw.server.domain.inventory.InventoryScopeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元验证：{@link StationScopeService} 的角色可见性口径与越权透传（模块四 · BC-5 / D10）。
 *
 * <p>关键点是"复用而非重造"：本服务必须把 {@code overrideStationId} 原样透传给
 * {@link InventoryScopeService#resolveCurrent(Long, Long)}（第一参数恒为 null），
 * 并把作用域等级映射成三层统一的 allowedStationIds 口径：
 * PLATFORM→null（不限制）、STATION→[自身站]、MANUFACTURER→下属寄售站集合、其它→空集。
 */
@ExtendWith(MockitoExtension.class)
class StationScopeServiceTest {

    private static final long STATION_ID = 7L;
    private static final long MFG_ID = 12L;

    @Mock
    private InventoryScopeService inventoryScopeService;

    @InjectMocks
    private StationScopeService scopeService;

    private InventoryScope.ScopeInfo info(InventoryScope.ScopeLevel level, List<Long> subs,
                                          Long mfgId, Long stationId) {
        PrincipalType type = switch (level) {
            case MANUFACTURER, PLATFORM -> PrincipalType.MANUFACTURER;
            case STATION -> PrincipalType.STATION;
            default -> null;
        };
        return new InventoryScope.ScopeInfo(type, stationId != null ? stationId : mfgId, false,
                level == InventoryScope.ScopeLevel.PLATFORM, level, subs, mfgId, stationId);
    }

    @Test
    @DisplayName("BC-5 平台管理员：allowedStationIds 返回 null（不加站过滤 = 全平台）")
    void platformAdmin_returnsNullMeaningUnrestricted() {
        when(inventoryScopeService.resolveCurrent(null, null))
                .thenReturn(info(InventoryScope.ScopeLevel.PLATFORM, List.of(), null, null));

        assertNull(scopeService.allowedStationIds(null),
                "平台管理员必须返回 null（语义=不限制），不能返回空集合（语义=无数据）");
    }

    @Test
    @DisplayName("BC-5 服务站：allowedStationIds = [自身站]，只能看自己")
    void station_returnsOnlyOwnStation() {
        when(inventoryScopeService.resolveCurrent(null, null))
                .thenReturn(info(InventoryScope.ScopeLevel.STATION, List.of(), null, STATION_ID));

        assertEquals(List.of(STATION_ID), scopeService.allowedStationIds(null));
    }

    @Test
    @DisplayName("BC-5 厂家：allowedStationIds = 下属寄售站集合（我的货寄在哪些站）")
    void manufacturer_returnsSubordinateStations() {
        when(inventoryScopeService.resolveCurrent(null, null))
                .thenReturn(info(InventoryScope.ScopeLevel.MANUFACTURER, List.of(3L, 7L, 9L), MFG_ID, null));

        assertEquals(List.of(3L, 7L, 9L), scopeService.allowedStationIds(null));
    }

    @Test
    @DisplayName("BC-5 未绑定主体（NONE）：返回空集 → 三层一律返回空数据，不抛 403")
    void none_returnsEmptySet() {
        when(inventoryScopeService.resolveCurrent(null, null))
                .thenReturn(info(InventoryScope.ScopeLevel.NONE, List.of(), null, null));

        List<Long> allowed = scopeService.allowedStationIds(null);
        assertTrue(allowed != null && allowed.isEmpty(), "NONE 必须是空集（无数据），而非 null（全平台）");
    }

    @Test
    @DisplayName("BC-5 商家（MERCHANT）：服务站域无数据 → 空集")
    void merchant_returnsEmptySet() {
        when(inventoryScopeService.resolveCurrent(null, null))
                .thenReturn(info(InventoryScope.ScopeLevel.MERCHANT, List.of(), null, null));

        assertTrue(scopeService.allowedStationIds(null).isEmpty());
    }

    @Test
    @DisplayName("BC-5 越权覆盖：InventoryScopeService 抛 40301 时原样透传（不被吞成空集）")
    void overrideForbidden_propagates40301() {
        when(inventoryScopeService.resolveCurrent(null, 88888L))
                .thenThrow(BizException.of(40301, "inventory.scope.forbidden"));

        BizException ex = assertThrows(BizException.class, () -> scopeService.allowedStationIds(88888L));

        assertEquals(40301, ex.getCode(), "越权覆盖必须映射为 HTTP 403");
    }

    @Test
    @DisplayName("D10 复用口径：overrideStationId 原样透传，manufacturerId 参数恒为 null（不重复造 scope 解析）")
    void delegatesOverrideStationIdVerbatim() {
        when(inventoryScopeService.resolveCurrent(null, STATION_ID))
                .thenReturn(info(InventoryScope.ScopeLevel.STATION, List.of(), null, STATION_ID));

        scopeService.allowedStationIds(STATION_ID);

        verify(inventoryScopeService).resolveCurrent(isNull(), eq(STATION_ID));
    }

    @Test
    @DisplayName("/me 作用域视图：level 与 allowedStationIds 一致，overrideStationId 回显")
    void resolveView_exposesLevelAndAllowedIds() {
        when(inventoryScopeService.resolveCurrent(null, STATION_ID))
                .thenReturn(info(InventoryScope.ScopeLevel.STATION, List.of(), null, STATION_ID));

        StationViews.StationScopeView view = scopeService.resolveView(STATION_ID);

        assertEquals(STATION_ID, view.overrideStationId());
        assertEquals("STATION", view.level());
        assertEquals(List.of(STATION_ID), view.allowedStationIds());
    }
}
