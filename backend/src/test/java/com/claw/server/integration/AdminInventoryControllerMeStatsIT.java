package com.claw.server.integration;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.InventoryViews;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.ClawUser;
import com.claw.server.domain.inventory.InventoryScope;
import com.claw.server.web.v1.AdminInventoryController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端（真实 PostgreSQL）验证：模块三库存双视图 / 统计报表端点，以及三条高压安全规则。
 *
 * <p>沿用 {@link AbstractIntegrationTest} 的真实库接线（localhost:5432/claw_it）。
 * 通过 {@code claw.security.dev-open-access=true} 放开 {@code @RequirePermission} 切面，
 * 直接调用控制器 Bean（仍经过 {@code InventoryScopeService} 与 {@code InventoryService} 全链路），
 * 用 {@link AuthContext} 注入不同登录主体，验证：
 * <ul>
 *   <li>四种主体的作用域正确性（M3-1/2/3）；</li>
 *   <li>下属服务站动态反查（只回"我的货寄在哪些站"，不依赖新表）；</li>
 *   <li>管理员统计聚合的计算正确性（M3-4）；</li>
 *   <li>安全三条：① 响应绝不含有 unitValue/valueCurrency/unitValueSource；
 *       ② 越权覆盖被拒抛 40301（不是 200 返回他人数据）；③ scopeLevel=NONE 返回空集合不返回 403。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = "claw.security.dev-open-access=true")
class AdminInventoryControllerMeStatsIT extends AbstractIntegrationTest {

    @Autowired
    private AdminInventoryController controller;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(long userId) {
        ClawUser user = new ClawUser(userId, "1380000" + userId, "MFG");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static String uuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /* ----------------------------- 数据准备 ----------------------------- */

    private Long insertUser(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.users (phone, full_name, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, "U-" + tag, "user-" + tag);
    }

    private void bindPrincipal(Long userId, String type, Long principalId) {
        jdbc.update("INSERT INTO claw.principal_bindings (user_id, principal_type, principal_id) VALUES (?, ?, ?)",
                userId, type, principalId);
    }

    private void grantPlatformAdmin(Long userId) {
        jdbc.update("INSERT INTO claw.user_roles (user_id, role_id) "
                        + "VALUES (?, (SELECT id FROM claw.roles WHERE code = 'PLATFORM_ADMIN'))",
                userId);
    }

    private Long insertManufacturer(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.manufacturers (code, name, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, "MFG-" + tag, "厂家-" + tag);
    }

    private Long insertStation(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.stations (code, name, country_code, status, open_hours, onboarding_status) "
                        + "VALUES (?, ?, 'KHM', 'ACTIVE', '24H', 'ACTIVATED') RETURNING id",
                Long.class, "ST-" + tag, "服务站-" + tag);
    }

    /** 建资产+设备+库存行（带 unit_value 货值快照，用于验证 DTO 收口剔除）。 */
    private void insertInventory(Long manufacturerId, String ownership, Long stationId,
                                 String status, String tag) {
        Long assetId = jdbc.queryForObject(
                "INSERT INTO claw.assets (asset_type, asset_no, status, manufacturer_id) "
                        + "VALUES ('BATTERY', ?, 'IN_STOCK', ?) RETURNING id",
                Long.class, "AST-" + tag, manufacturerId);
        Long deviceId = jdbc.queryForObject(
                "INSERT INTO claw.devices (asset_id, device_type, status) VALUES (?, 'BATTERY_BMS', 'ACTIVE') RETURNING id",
                Long.class, assetId);
        jdbc.update(
                "INSERT INTO claw.inventory (asset_id, device_id, ownership_type, owner_manufacturer_id, "
                        + "holder_station_id, current_status, serial_number, unit_value, value_currency, unit_value_source) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 100.00, 'USD', 'MANUAL')",
                assetId, deviceId, ownership, manufacturerId, stationId, status, "SN-" + tag);
    }

    /* ----------------------------- 安全规则①：敏感字段不下发 ----------------------------- */

    private void assertNoSensitiveFields(Object payload) throws Exception {
        String json = objectMapper.writeValueAsString(payload);
        JsonNode root = objectMapper.readTree(json);
        assertNoSensitiveKey(root, Set.of("unitValue", "valueCurrency", "unitValueSource"));
    }

    private void assertNoSensitiveKey(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                assertFalse(keys.contains(e.getKey()),
                        "响应体不应包含敏感货值字段: " + e.getKey());
                assertNoSensitiveKey(e.getValue(), keys);
            });
        } else if (node.isArray()) {
            node.forEach(n -> assertNoSensitiveKey(n, keys));
        }
    }

    /* ----------------------------- 1) 厂家双视图 + 动态反查 ----------------------------- */

    @Test
    @DisplayName("厂家 /me：current=OWNED_BY_MFG，subordinate=动态反查 CONSIGNED 站点，且不含货值字段")
    void manufacturerMeScope() throws Exception {
        String tag = uuid();
        Long u = insertUser(tag);
        Long m = insertManufacturer(tag);
        Long s1 = insertStation(tag + "-S1");
        Long s2 = insertStation(tag + "-S2");
        Long sOther = insertStation(tag + "-SO");
        Long mOther = insertManufacturer(tag + "-M2");
        bindPrincipal(u, "MANUFACTURER", m);

        insertInventory(m, "OWNED_BY_MFG", null, "IN_FACTORY", tag + "-o1");
        insertInventory(m, "OWNED_BY_MFG", null, "IN_FACTORY", tag + "-o2");
        insertInventory(m, "CONSIGNED", s1, "AT_STATION", tag + "-c1");
        insertInventory(m, "CONSIGNED", s1, "AT_STATION", tag + "-c2");
        insertInventory(m, "CONSIGNED", s2, "AT_STATION", tag + "-c3");
        // 噪声：其他厂家的寄售不应出现在 m 的下属集合里
        insertInventory(mOther, "CONSIGNED", sOther, "AT_STATION", tag + "-noise");

        loginAs(u);
        ApiResult<InventoryViews.InventoryScopeView> res = controller.me(null, null, null, null, false, true, 500);
        InventoryViews.InventoryScopeView v = res.data();
        InventoryViews.ScopeInfoView scope = v.scope();

        assertEquals(InventoryScope.ScopeLevel.MANUFACTURER.name(), scope.scopeLevel());
        assertEquals(m, scope.effectiveManufacturerId());
        // 下属站点 = 动态反查（仅 m 的 consigned 站点），不含他人站点
        assertEquals(2, scope.subordinateStationIds().size());
        assertTrue(scope.subordinateStationIds().containsAll(List.of(s1, s2)));
        // current = 2 台 OWNED_BY_MFG
        assertEquals(2, v.currentTotal());
        assertEquals(2, v.current().size());
        assertTrue(v.current().stream().allMatch(r ->
                r.ownershipType() == OwnershipType.OWNED_BY_MFG && m.equals(r.ownerManufacturerId())));
        // subordinate 分组
        assertEquals(3, v.subordinateTotal());
        Map<Long, InventoryViews.StationGroupView> groups = new HashMap<>();
        v.subordinateStations().forEach(g -> groups.put(g.stationId(), g));
        assertTrue(groups.containsKey(s1) && groups.get(s1).total() == 2);
        assertTrue(groups.containsKey(s2) && groups.get(s2).total() == 1);
        assertFalse(groups.containsKey(sOther));

        assertNoSensitiveFields(res);
    }

    /* ----------------------------- 2) 服务站双视图 ----------------------------- */

    @Test
    @DisplayName("服务站 /me：current=本站在库 CONSIGNED，subordinate 空，且不含货值字段")
    void stationMeScope() throws Exception {
        String tag = uuid();
        Long u = insertUser(tag);
        Long s = insertStation(tag + "-S");
        Long sOther = insertStation(tag + "-SO");
        Long m = insertManufacturer(tag);
        bindPrincipal(u, "STATION", s);

        insertInventory(m, "CONSIGNED", s, "AT_STATION", tag + "-c1");
        insertInventory(m, "CONSIGNED", s, "AT_STATION", tag + "-c2");
        insertInventory(m, "CONSIGNED", s, "AT_STATION", tag + "-c3");
        insertInventory(m, "CONSIGNED", sOther, "AT_STATION", tag + "-noise");

        loginAs(u);
        ApiResult<InventoryViews.InventoryScopeView> res = controller.me(null, null, null, null, false, true, 500);
        InventoryViews.InventoryScopeView v = res.data();

        assertEquals(InventoryScope.ScopeLevel.STATION.name(), v.scope().scopeLevel());
        assertEquals(s, v.scope().effectiveStationId());
        assertTrue(v.scope().subordinateStationIds().isEmpty());
        assertEquals(3, v.currentTotal());
        assertTrue(v.current().stream().allMatch(r ->
                r.ownershipType() == OwnershipType.CONSIGNED && s.equals(r.holderStationId())));
        assertTrue(v.subordinateStations().isEmpty());

        assertNoSensitiveFields(res);
    }

    /* ----------------------------- 3) 管理员统计聚合 ----------------------------- */

    @Test
    @DisplayName("平台管理员 /stats：全平台聚合，结构自洽，scopeLevel=PLATFORM，不含货值字段")
    void platformAdminStats() throws Exception {
        String tag = uuid();
        Long u = insertUser(tag);
        grantPlatformAdmin(u);

        loginAs(u);
        ApiResult<InventoryViews.InventoryStatsView> res = controller.stats(null, null);
        InventoryViews.InventoryStatsView s = res.data();

        assertEquals(InventoryScope.ScopeLevel.PLATFORM.name(), s.scopeLevel());
        // 聚合自洽：三类所有权之和 == total
        assertEquals(s.ownedByMfgTotal() + s.consignedTotal() + s.fullTotal(), s.total());
        double expectedRatio = s.total() == 0 ? 0.0 : (double) s.consignedTotal() / s.total();
        assertEquals(expectedRatio, s.consignedRatio(), 1e-9);
        assertEquals(s.byStation().size(), s.stationCount());
        assertTrue(s.byStation().size() <= 50, "byStation 必须截断在 Top50");
        assertEquals(s.byManufacturer().size(), s.manufacturerCount());
        assertTrue(s.atFactoryCount() >= 0);

        assertNoSensitiveFields(res);
    }

    @Test
    @DisplayName("厂家 /stats：按 owner_manufacturer_id 收窄，数值精确（含 ratio 不除零）")
    void manufacturerStatsDeterministic() throws Exception {
        String tag = uuid();
        Long u = insertUser(tag);
        Long m = insertManufacturer(tag);
        Long sa = insertStation(tag + "-SA");
        Long sb = insertStation(tag + "-SB");
        bindPrincipal(u, "MANUFACTURER", m);

        insertInventory(m, "OWNED_BY_MFG", null, "IN_FACTORY", tag + "-o1");
        insertInventory(m, "OWNED_BY_MFG", null, "IN_FACTORY", tag + "-o2");
        insertInventory(m, "CONSIGNED", sa, "AT_STATION", tag + "-c1");
        insertInventory(m, "CONSIGNED", sa, "AT_STATION", tag + "-c2");
        insertInventory(m, "CONSIGNED", sa, "AT_STATION", tag + "-c3");
        insertInventory(m, "CONSIGNED", sb, "AT_STATION", tag + "-c4");
        insertInventory(m, "FULL", null, "SOLD", tag + "-f1");

        loginAs(u);
        ApiResult<InventoryViews.InventoryStatsView> res = controller.stats(m, null);
        InventoryViews.InventoryStatsView s = res.data();

        assertEquals(InventoryScope.ScopeLevel.MANUFACTURER.name(), s.scopeLevel());
        assertEquals(7, s.total());
        assertEquals(2, s.ownedByMfgTotal());
        assertEquals(4, s.consignedTotal());
        assertEquals(1, s.fullTotal());
        assertEquals(4.0 / 7.0, s.consignedRatio(), 1e-9);
        // atFactory = holder_station_id IS NULL 且 owner=m → owned(2) + full(1) = 3
        assertEquals(3, s.atFactoryCount());

        Map<String, Long> byStatus = new HashMap<>();
        s.byStatus().forEach(c -> byStatus.put(c.status(), c.count()));
        assertEquals(2L, byStatus.getOrDefault("IN_FACTORY", 0L));
        assertEquals(4L, byStatus.getOrDefault("AT_STATION", 0L));
        assertEquals(1L, byStatus.getOrDefault("SOLD", 0L));

        Map<Long, Long> byStation = new HashMap<>();
        s.byStation().forEach(c -> byStation.put(c.stationId(), c.count()));
        assertEquals(3L, byStation.getOrDefault(sa, 0L));
        assertEquals(1L, byStation.getOrDefault(sb, 0L));
        assertEquals(2, s.stationCount());

        Map<Long, Long> byMfg = new HashMap<>();
        s.byManufacturer().forEach(c -> byMfg.put(c.manufacturerId(), c.count()));
        assertEquals(7L, byMfg.getOrDefault(m, 0L));
        assertEquals(1, s.manufacturerCount());

        Map<String, Long> byOwn = new HashMap<>();
        s.byOwnership().forEach(c -> byOwn.put(c.ownershipType(), c.count()));
        assertEquals(2L, byOwn.getOrDefault("OWNED_BY_MFG", 0L));
        assertEquals(4L, byOwn.getOrDefault("CONSIGNED", 0L));
        assertEquals(1L, byOwn.getOrDefault("FULL", 0L));

        assertNoSensitiveFields(res);
    }

    @Test
    @DisplayName("厂家 /stats：零库存时 total=0，consignedRatio=0（不除零）")
    void manufacturerStatsEmptyNoDivZero() throws Exception {
        String tag = uuid();
        Long u = insertUser(tag);
        Long m = insertManufacturer(tag);
        bindPrincipal(u, "MANUFACTURER", m);

        loginAs(u);
        ApiResult<InventoryViews.InventoryStatsView> res = controller.stats(m, null);
        InventoryViews.InventoryStatsView s = res.data();

        assertEquals(0, s.total());
        assertEquals(0.0, s.consignedRatio(), 1e-9);
        assertNoSensitiveFields(res);
    }

    /* ----------------------------- 安全规则②：越权覆盖失败关闭 ----------------------------- */

    @Test
    @DisplayName("安全规则②：厂家越权覆盖 manufacturerId 被拒（BizException 40301）")
    void overrideManufacturerWrongMfg_rejected() {
        String tag = uuid();
        Long u = insertUser(tag);
        Long m = insertManufacturer(tag);
        bindPrincipal(u, "MANUFACTURER", m);

        loginAs(u);
        BizException ex = assertThrows(BizException.class,
                () -> controller.me(99999L, null, null, null, false, true, 500));
        assertEquals(40301, ex.getCode());
        assertEquals("inventory.scope.forbidden", ex.getMessageCode());
    }

    @Test
    @DisplayName("安全规则②：服务站越权覆盖 stationId 被拒（BizException 40301）")
    void overrideStationWrongStation_rejected() {
        String tag = uuid();
        Long u = insertUser(tag);
        Long s = insertStation(tag + "-S");
        bindPrincipal(u, "STATION", s);

        loginAs(u);
        BizException ex = assertThrows(BizException.class,
                () -> controller.me(null, 88888L, null, null, false, true, 500));
        assertEquals(40301, ex.getCode());
    }

    @Test
    @DisplayName("安全规则②：厂家越权覆盖 /stats manufacturerId 被拒（BizException 40301）")
    void overrideManufacturerWrongMfgStats_rejected() {
        String tag = uuid();
        Long u = insertUser(tag);
        Long m = insertManufacturer(tag);
        bindPrincipal(u, "MANUFACTURER", m);

        loginAs(u);
        BizException ex = assertThrows(BizException.class, () -> controller.stats(99999L, null));
        assertEquals(40301, ex.getCode());
    }

    @Test
    @DisplayName("越权覆盖：厂家用自身 manufacturerId 收窄被放行")
    void overrideManufacturerOwnId_allowed() throws Exception {
        String tag = uuid();
        Long u = insertUser(tag);
        Long m = insertManufacturer(tag);
        bindPrincipal(u, "MANUFACTURER", m);
        insertInventory(m, "OWNED_BY_MFG", null, "IN_FACTORY", tag + "-o1");

        loginAs(u);
        ApiResult<InventoryViews.InventoryScopeView> res = controller.me(m, null, null, null, false, true, 500);
        assertEquals(InventoryScope.ScopeLevel.MANUFACTURER.name(), res.data().scope().scopeLevel());
    }

    /* ----------------------------- 安全规则③：NONE 不返回 403 ----------------------------- */

    @Test
    @DisplayName("安全规则③：未绑定主体 /me 返回 scopeLevel=NONE + 空集合，不抛 403")
    void noneScopeMe_returnsEmptyNotForbidden() throws Exception {
        String tag = uuid();
        Long u = insertUser(tag); // 无 principal_binding，非平台管理员

        loginAs(u);
        ApiResult<InventoryViews.InventoryScopeView> res = controller.me(null, null, null, null, false, true, 500);
        InventoryViews.InventoryScopeView v = res.data();

        assertEquals(InventoryScope.ScopeLevel.NONE.name(), v.scope().scopeLevel());
        assertTrue(v.current().isEmpty());
        assertTrue(v.subordinateStations().isEmpty());
        assertNoSensitiveFields(res);
    }

    @Test
    @DisplayName("安全规则③：未绑定主体 /stats 返回 scopeLevel=NONE，不抛 403")
    void noneScopeStats_returnsNoneNotForbidden() throws Exception {
        String tag = uuid();
        Long u = insertUser(tag);

        loginAs(u);
        ApiResult<InventoryViews.InventoryStatsView> res = controller.stats(null, null);
        assertEquals(InventoryScope.ScopeLevel.NONE.name(), res.data().scopeLevel());
    }
}
