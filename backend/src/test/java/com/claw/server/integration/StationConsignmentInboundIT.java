package com.claw.server.integration;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.enums.OnboardingStatus;
import com.claw.server.common.security.ClawUser;
import com.claw.server.domain.inventory.InventoryService;
import com.claw.server.domain.station.StationScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务站自主寄售入库（V82 · {@code POST /api/v1/station/consignment/inbound}）真库回归。
 *
 * <h2>为什么必须有这组测试</h2>
 * 上一轮改造（厂家「选站分拨」下线 → 服务站自主入库）只做了人工端到端冒烟，
 * <b>新接口零自动化测试</b>。本次把改造的核心契约全部钉死在真实 PostgreSQL 上：
 * <ol>
 *   <li>四处写入齐全（占有权 / 库存 / 设备状态 / 生命周期事件），且<b>货权不转移</b>；</li>
 *   <li><b>安全断言</b>：请求体里强塞 {@code stationId} / {@code manufacturerId} 一律被忽略 ——
 *       站点只能来自登录作用域、厂家只能来自 {@code inventory.owner_manufacturer_id}；</li>
 *   <li>重复入库 / 跨站抢货被明确拒绝（40945 / 40944），不再静默改别人的占有权；</li>
 *   <li>授信额度硬阻断（40941）留痕且<b>事务回滚干净</b>；</li>
 *   <li>非服务站主体（厂家 / 平台管理员）一律 40301 —— 「谁操作数据是谁的」。</li>
 * </ol>
 *
 * <h2>装配方式</h2>
 * 数据与断言风格沿用同目录 {@link ConsignmentCustodyIT}（真实 PostgreSQL，见
 * {@link AbstractIntegrationTest}）。控制器层的两个用例走 MockMvc：
 * {@code addFilters = false} 关掉 JWT 过滤器链，登录态由测试直接写入
 * {@code SecurityContextHolder}（MockMvc 与测试同线程，控制器里
 * {@code AuthContext.currentUserId()} 能读到），从而真实跑通
 * 「HTTP 请求 → {@code @RequirePermission} 切面 → 控制器 → 服务 → 落库」全链路。
 */
@AutoConfigureMockMvc(addFilters = false)
class StationConsignmentInboundIT extends AbstractIntegrationTest {

    private static final String INBOUND_URL = "/api/v1/station/consignment/inbound";
    private static final String PERMISSION = "station:consignment:inbound";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InventoryService inventoryService;

    @Autowired
    StationScopeService scopeService;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* ① 正常入库：四处写入齐全 + 货权不变                                  */
    /* ================================================================== */

    @Test
    @DisplayName("正常入库：占有权/库存/设备状态/生命周期事件四处写入齐全，且货权厂家不变")
    void inboundWritesFourPlacesAndKeepsOwnership() {
        String tag = uuid();
        Long manufacturerId = insertManufacturer(tag);
        Long stationId = insertStation(tag + "-A");
        Long deviceId = insertDevice(tag, manufacturerId, 1);
        Long ownerBefore = ownerManufacturerOf(deviceId);
        assertEquals(manufacturerId, ownerBefore);

        var result = inventoryService.stationConsignmentInbound(deviceId, stationId, null);

        assertEquals(manufacturerId, result.manufacturerId(), "货权厂家必须由 inventory.owner_manufacturer_id 带出");
        assertEquals(stationId, result.stationId());
        assertNotNull(result.custodyId(), "入库结果应回带占有权 ID，便于前端回显");
        assertNotNull(result.inboundAt(), "入库结果应回带入站时点");

        // ① claw.consignment_custodies：未结束的占有权，持有站=本站、货权方=厂家
        assertEquals(1, countActiveCustody(deviceId), "入库后应有且仅有一条未结束占有权");
        assertEquals(stationId, holderStationOf(deviceId));
        assertEquals(manufacturerId, custodyManufacturerOf(deviceId));
        assertEquals("ACTIVE", custodyStatusOf(deviceId));

        // ② claw.inventory：转寄售在站，货权方不变（货权不转移是本改造的红线）
        assertEquals("CONSIGNED", str("SELECT ownership_type FROM claw.inventory WHERE device_id = ?", deviceId));
        assertEquals("AT_STATION", str("SELECT current_status FROM claw.inventory WHERE device_id = ?", deviceId));
        assertEquals(stationId, lng("SELECT holder_station_id FROM claw.inventory WHERE device_id = ?", deviceId));
        assertNotNull(lng("SELECT custody_id FROM claw.inventory WHERE device_id = ?", deviceId));
        assertNotNull(jdbc.queryForObject(
                "SELECT inbound_at FROM claw.inventory WHERE device_id = ?", java.sql.Timestamp.class, deviceId));
        assertEquals(ownerBefore, ownerManufacturerOf(deviceId), "入库动作不得改变货权方");

        // ③ claw.devices.lifecycle_status
        assertEquals("AT_STATION", deviceLifecycleStatus(deviceId));

        // ④ claw.lifecycle_events：RECEIVE 事件留痕
        assertEquals(1, countLifecycleEvents(deviceId, "RECEIVE"), "入库应写一条 RECEIVE 生命周期事件");
        assertEquals(stationId, lng("SELECT station_id FROM claw.lifecycle_events "
                + "WHERE device_id = ? AND event_type = 'RECEIVE'", deviceId));
    }

    /* ================================================================== */
    /* ①b 扫码枪：按设备编号（device_no）入库                                */
    /* ================================================================== */

    @Test
    @DisplayName("扫码枪：按设备编号入库成功，落库结果与按 ID 入库逐项一致（同一条写入链）")
    void inboundByDeviceNoWritesExactlyLikeById() {
        String tag = uuid();
        Long manufacturerId = insertManufacturer(tag);
        Long stationId = insertStation(tag + "-A");

        // 一台走「扫码编号」路径、一台走「主键」路径，两条路径的落库必须逐项一致
        Long byNoDeviceId = insertDevice(tag + "-n", manufacturerId, 1);
        String deviceNo = "DEV-" + tag + "-1";
        setDeviceNo(byNoDeviceId, deviceNo);
        Long byIdDeviceId = insertDevice(tag + "-i", manufacturerId, 2);

        var byNo = inventoryService.stationConsignmentInboundByNo(deviceNo, stationId, null);
        var byId = inventoryService.stationConsignmentInbound(byIdDeviceId, stationId, null);

        // ① 返回值：编号被正确翻译成主键，货权/站点与按 ID 入库完全一致
        assertEquals(byNoDeviceId, byNo.deviceId(), "设备编号必须被正确翻译成设备主键");
        assertEquals(byIdDeviceId, byId.deviceId());
        assertEquals(byId.manufacturerId(), byNo.manufacturerId(), "货权厂家都必须由 inventory.owner_manufacturer_id 带出");
        assertEquals(byId.stationId(), byNo.stationId());
        assertNotNull(byNo.custodyId(), "按编号入库也要回带占有权 ID，便于前端回显");
        assertNotNull(byNo.inboundAt(), "按编号入库也要回带入站时点");

        // ② claw.consignment_custodies：与按 ID 入库逐项比对
        assertEquals(countActiveCustody(byIdDeviceId), countActiveCustody(byNoDeviceId),
                "按编号入库后应同按 ID 一样只有一条未结束占有权");
        assertEquals(1, countActiveCustody(byNoDeviceId));
        assertEquals(holderStationOf(byIdDeviceId), holderStationOf(byNoDeviceId));
        assertEquals(custodyManufacturerOf(byIdDeviceId), custodyManufacturerOf(byNoDeviceId));
        assertEquals(custodyStatusOf(byIdDeviceId), custodyStatusOf(byNoDeviceId));
        assertEquals(stationId, holderStationOf(byNoDeviceId));
        assertEquals(manufacturerId, custodyManufacturerOf(byNoDeviceId));
        assertEquals("ACTIVE", custodyStatusOf(byNoDeviceId));

        // ③ claw.inventory：与按 ID 入库逐项比对，且货权不转移
        assertEquals(inv(byIdDeviceId, "ownership_type"), inv(byNoDeviceId, "ownership_type"));
        assertEquals(inv(byIdDeviceId, "current_status"), inv(byNoDeviceId, "current_status"));
        assertEquals("CONSIGNED", inv(byNoDeviceId, "ownership_type"));
        assertEquals("AT_STATION", inv(byNoDeviceId, "current_status"));
        assertEquals(stationId, lng("SELECT holder_station_id FROM claw.inventory WHERE device_id = ?", byNoDeviceId));
        assertNotNull(lng("SELECT custody_id FROM claw.inventory WHERE device_id = ?", byNoDeviceId));
        assertNotNull(jdbc.queryForObject(
                "SELECT inbound_at FROM claw.inventory WHERE device_id = ?", java.sql.Timestamp.class, byNoDeviceId));
        assertEquals(manufacturerId, ownerManufacturerOf(byNoDeviceId), "入库动作不得改变货权方");

        // ④ 设备生命周期状态 + RECEIVE 事件
        assertEquals(deviceLifecycleStatus(byIdDeviceId), deviceLifecycleStatus(byNoDeviceId));
        assertEquals("AT_STATION", deviceLifecycleStatus(byNoDeviceId));
        assertEquals(countLifecycleEvents(byIdDeviceId, "RECEIVE"), countLifecycleEvents(byNoDeviceId, "RECEIVE"));
        assertEquals(1, countLifecycleEvents(byNoDeviceId, "RECEIVE"));
        assertEquals(stationId, lng("SELECT station_id FROM claw.lifecycle_events "
                + "WHERE device_id = ? AND event_type = 'RECEIVE'", byNoDeviceId));

        // ⑤ 复用同一写入链的旁证：同一编号重复扫码，命中与按 ID 完全相同的 40945
        BizException dup = assertThrows(BizException.class,
                () -> inventoryService.stationConsignmentInboundByNo(deviceNo, stationId, null),
                "按编号重复入库必须和按 ID 重复入库走同一套拦截");
        assertEquals(40945, dup.getCode());
        assertEquals("inventory.custody.duplicate.inbound", dup.getMessageCode());
    }

    @Test
    @DisplayName("40401：扫码编号在系统里查不到设备 → HTTP 404，且一处都不写")
    void unknownDeviceNoRejectedAndWritesNothing() {
        String tag = uuid();
        Long manufacturerId = insertManufacturer(tag);
        Long stationId = insertStation(tag + "-A");
        // 先放一台真实设备在台账里：证明下面的「零写入」不是因为库里本来就没数据
        Long existingDeviceId = insertDevice(tag, manufacturerId, 1);

        // 快照口径限定在本用例独占的站点上，不受其它用例历史数据干扰
        int custodyBefore = countByStation("claw.consignment_custodies", "holder_station_id", stationId);
        int eventsBefore = countByStation("claw.lifecycle_events", "station_id", stationId);
        int atStationBefore = countByStation("claw.inventory", "holder_station_id", stationId);
        assertEquals(0, custodyBefore);
        assertEquals(0, eventsBefore);
        assertEquals(0, atStationBefore);

        BizException e = assertThrows(BizException.class,
                () -> inventoryService.stationConsignmentInboundByNo("NO-SUCH-" + tag, stationId, null),
                "扫到的编号查不到设备必须明确报错，不能静默建一条无主占有权");
        assertEquals(40401, e.getCode());
        assertEquals("device.not.found.by.no", e.getMessageCode());
        assertEquals(HttpStatus.NOT_FOUND, e.httpStatus());

        // 一处都不写：本站点下没有新增任何占有权 / 生命周期事件 / 在站库存
        assertEquals(custodyBefore, countByStation("claw.consignment_custodies", "holder_station_id", stationId),
                "编号查不到时不得新增占有权");
        assertEquals(eventsBefore, countByStation("claw.lifecycle_events", "station_id", stationId),
                "编号查不到时不得写生命周期事件");
        assertEquals(atStationBefore, countByStation("claw.inventory", "holder_station_id", stationId),
                "编号查不到时不得把任何库存改成在站");
        // 既有设备也不受牵连
        assertEquals(0, countActiveCustody(existingDeviceId));
        assertEquals("PRODUCING", inv(existingDeviceId, "current_status"));
        assertEquals(0, countLifecycleEvents(existingDeviceId, "RECEIVE"));

        // 边界：空白编号等价于没扫到 → 10001（HTTP 400），而不是 404
        BizException blank = assertThrows(BizException.class,
                () -> inventoryService.stationConsignmentInboundByNo("   ", stationId, null));
        assertEquals(10001, blank.getCode());
        assertEquals("station.consignment.inbound.device_required", blank.getMessageCode());
        assertEquals(HttpStatus.BAD_REQUEST, blank.httpStatus());
    }

    /* ================================================================== */
    /* ② 安全断言（最关键）：请求体里的 stationId / manufacturerId 被忽略     */
    /* ================================================================== */

    @Test
    @DisplayName("请求体强塞 stationId / manufacturerId 一律被忽略：站点取登录作用域、厂家取货权方")
    void injectedStationAndManufacturerAreIgnored() throws Exception {
        String tag = uuid();
        Long ownerMfg = insertManufacturer(tag);
        Long myStation = insertStation(tag + "-A");
        Long otherStation = insertStation(tag + "-B");
        Long otherMfg = insertManufacturer(tag + "-X");
        Long deviceId = insertDevice(tag, ownerMfg, 1);

        Long userId = insertUser(tag);
        bindPrincipal(userId, "STATION", myStation);
        grantInboundPermission(userId);
        setAuth(userId);

        // 前端残留字段 / 攻击者构造：把站点与厂家塞进请求体
        String body = "{\"deviceId\":" + deviceId
                + ",\"stationId\":" + otherStation
                + ",\"manufacturerId\":" + otherMfg + "}";

        // DTO 结构上就没有这两个字段：Jackson 静默丢弃未知属性，只留下 deviceId
        StationRequests.StationConsignmentInbound parsed =
                objectMapper.readValue(body, StationRequests.StationConsignmentInbound.class);
        assertEquals(deviceId, parsed.deviceId());
        // V82 扫码枪改造：DTO 多了 deviceNo（设备编号），但站点/厂家仍不在入参里
        assertEquals(List.of("deviceId", "deviceNo"), recordComponents());

        mockMvc.perform(post(INBOUND_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.stationId").value(myStation))
                .andExpect(jsonPath("$.data.manufacturerId").value(ownerMfg));

        // 落库口径：既不是 injected 的站、也不是 injected 的厂家
        assertEquals(myStation, holderStationOf(deviceId), "落库 stationId 必须是登录作用域的站");
        assertNotEquals(otherStation, holderStationOf(deviceId), "注入的 stationId 必须被忽略");
        assertEquals(ownerMfg, custodyManufacturerOf(deviceId), "落库 manufacturerId 必须是货权方");
        assertNotEquals(otherMfg, custodyManufacturerOf(deviceId), "注入的 manufacturerId 必须被忽略");
        assertEquals(ownerMfg, ownerManufacturerOf(deviceId), "货权方不因入库改变");
        assertEquals(myStation, lng("SELECT station_id FROM claw.lifecycle_events "
                + "WHERE device_id = ? AND event_type = 'RECEIVE'", deviceId));
    }

    /* ================================================================== */
    /* ③ 40944 他站已占有 / 40945 本站重复入库                              */
    /* ================================================================== */

    @Test
    @DisplayName("40944：设备已被其它服务站占有，本站无法入库")
    void heldByOtherStationRejected() {
        String tag = uuid();
        Long manufacturerId = insertManufacturer(tag);
        Long stationA = insertStation(tag + "-A");
        Long stationB = insertStation(tag + "-B");
        Long deviceId = insertDevice(tag, manufacturerId, 1);

        inventoryService.stationConsignmentInbound(deviceId, stationA, null);

        BizException e = assertThrows(BizException.class,
                () -> inventoryService.stationConsignmentInbound(deviceId, stationB, null),
                "他站已占有的设备不得被本站在未经调拨的情况下抢走");
        assertEquals(40944, e.getCode());
        assertEquals("inventory.custody.held.by.other.station", e.getMessageCode());

        // 拒绝后原占有权不受影响
        assertEquals(stationA, holderStationOf(deviceId));
        assertEquals(1, countActiveCustody(deviceId));
    }

    @Test
    @DisplayName("40945：本站重复入库（扫码重复提交）被拒绝")
    void duplicateInboundRejected() {
        String tag = uuid();
        Long manufacturerId = insertManufacturer(tag);
        Long stationId = insertStation(tag + "-A");
        Long deviceId = insertDevice(tag, manufacturerId, 1);

        inventoryService.stationConsignmentInbound(deviceId, stationId, null);

        BizException e = assertThrows(BizException.class,
                () -> inventoryService.stationConsignmentInbound(deviceId, stationId, null),
                "同一台设备在本站重复入库必须明确报错，不能静默覆盖");
        assertEquals(40945, e.getCode());
        assertEquals("inventory.custody.duplicate.inbound", e.getMessageCode());
        assertEquals(1, countActiveCustody(deviceId), "重复入库失败后仍应只有一条未结束占有权");
    }

    /* ================================================================== */
    /* ④ 40941 授信额度硬阻断：留痕 + 事务回滚干净                          */
    /* ================================================================== */

    @Test
    @DisplayName("40941：超出授信额度硬阻断，onboarding_credit_blocks 留痕且事务回滚干净")
    void creditLimitExceededLeavesBlockAndRollsBack() {
        String tag = uuid();
        Long manufacturerId = insertManufacturer(tag);
        // 额度 10.00 < 设备货值 100.00 —— 必然触发硬阻断
        Long stationId = insertStationWithCreditLimit(tag + "-A", new BigDecimal("10.00"));
        Long deviceId = insertDevice(tag, manufacturerId, 1);
        int blocksBefore = countCreditBlocks(stationId);

        BizException e = assertThrows(BizException.class,
                () -> inventoryService.stationConsignmentInbound(deviceId, stationId, null));
        assertEquals(40941, e.getCode());
        assertEquals("credit.limit.exceeded", e.getMessageCode());

        // ① 风控留痕（REQUIRES_NEW 独立事务，不随业务回滚被抹掉）
        assertEquals(blocksBefore + 1, countCreditBlocks(stationId),
                "超限必须在 onboarding_credit_blocks 留下 CONSIGN_SHIP 痕迹");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM claw.onboarding_credit_blocks "
                        + "WHERE principal_type = 'STATION' AND principal_id = ? AND scene = 'CONSIGN_SHIP' "
                        + "AND id = (SELECT max(id) FROM claw.onboarding_credit_blocks WHERE principal_id = ?)",
                Integer.class, stationId, stationId));

        // ② 事务回滚干净：库存没被写成寄售在站、也没有残留占有权
        assertEquals("PRODUCING", str("SELECT current_status FROM claw.inventory WHERE device_id = ?", deviceId),
                "阻断后库存状态必须回滚为入库前");
        assertNull(lng("SELECT holder_station_id FROM claw.inventory WHERE device_id = ?", deviceId),
                "阻断后不得残留持有站");
        assertNull(lng("SELECT custody_id FROM claw.inventory WHERE device_id = ?", deviceId),
                "阻断后不得残留占有权关联");
        assertEquals(0, countActiveCustody(deviceId), "阻断后不得残留未结束占有权");
        assertEquals(0, countLifecycleEvents(deviceId, "RECEIVE"), "阻断后不得写入生命周期事件");
        assertNotEquals("AT_STATION", deviceLifecycleStatus(deviceId));
    }

    /* ================================================================== */
    /* ⑤ 非 STATION 主体 → 40301                                           */
    /* ================================================================== */

    @Test
    @DisplayName("40301：厂家主体 / 平台管理员都不是确定的服务站，寄售入库一律拒绝")
    void nonStationPrincipalRejected() {
        String tag = uuid();
        Long manufacturerId = insertManufacturer(tag);

        // 厂家主体：即使拿到了权限位，也拿不到「我是哪个站」
        Long mfgUser = insertUser(tag + "-m");
        bindPrincipal(mfgUser, "MANUFACTURER", manufacturerId);
        grantInboundPermission(mfgUser);
        setAuth(mfgUser);

        BizException e = assertThrows(BizException.class, () -> scopeService.currentStationId());
        assertEquals(40301, e.getCode());
        assertEquals("station.consignment.inbound.station_required", e.getMessageCode());
        assertEquals(HttpStatus.FORBIDDEN, e.httpStatus());

        // 平台管理员：作用域是 PLATFORM（不限制），同样不是某个确定的服务站
        Long adminUser = insertUser(tag + "-a");
        grantRole(adminUser, requireRoleId("PLATFORM_ADMIN"));
        setAuth(adminUser);

        BizException adminEx = assertThrows(BizException.class, () -> scopeService.currentStationId());
        assertEquals(40301, adminEx.getCode());
        assertEquals("station.consignment.inbound.station_required", adminEx.getMessageCode());
    }

    @Test
    @DisplayName("Controller 层：厂家主体调寄售入库接口返回 HTTP 403 / code 40301")
    void manufacturerUserGetsHttp403() throws Exception {
        String tag = uuid();
        Long manufacturerId = insertManufacturer(tag);
        Long stationId = insertStation(tag + "-A");
        Long deviceId = insertDevice(tag, manufacturerId, 1);

        Long mfgUser = insertUser(tag + "-m");
        bindPrincipal(mfgUser, "MANUFACTURER", manufacturerId);
        // 故意把权限位也发给厂家账号：证明 403 来自「不是服务站主体」而非「缺权限位」
        grantInboundPermission(mfgUser);
        setAuth(mfgUser);

        mockMvc.perform(post(INBOUND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":" + deviceId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        // 403 之后不得产生任何写入（失败关闭）
        assertEquals(0, countActiveCustody(deviceId));
        assertEquals("PRODUCING", str("SELECT current_status FROM claw.inventory WHERE device_id = ?", deviceId));
        assertNotEquals(stationId, lng("SELECT holder_station_id FROM claw.inventory WHERE device_id = ?", deviceId));
    }

    @Test
    @DisplayName("Controller 层：服务站主体调寄售入库接口返回 200，站点为登录作用域的站")
    void stationUserGetsHttp200WithOwnStation() throws Exception {
        String tag = uuid();
        Long manufacturerId = insertManufacturer(tag);
        Long myStation = insertStation(tag + "-A");
        Long deviceId = insertDevice(tag, manufacturerId, 1);

        Long stationUser = insertUser(tag + "-s");
        bindPrincipal(stationUser, "STATION", myStation);
        grantInboundPermission(stationUser);
        setAuth(stationUser);

        mockMvc.perform(post(INBOUND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":" + deviceId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deviceId").value(deviceId))
                .andExpect(jsonPath("$.data.stationId").value(myStation))
                .andExpect(jsonPath("$.data.manufacturerId").value(manufacturerId));

        assertEquals(myStation, holderStationOf(deviceId));
    }

    /* ================================================================== */
    /* ⑥ 失败关闭：台账缺货权方 / 设备不在台账 / 缺 deviceId                 */
    /* ================================================================== */

    @Test
    @DisplayName("40943：库存台账缺货权厂家，宁可拒绝入库也不产生无主占有权")
    void missingOwnerManufacturerRejected() {
        String tag = uuid();
        Long stationId = insertStation(tag + "-A");
        // 台账没有 owner_manufacturer_id（历史脏数据 / 厂家未补全）
        Long deviceId = insertDeviceWithoutOwner(tag, 1);
        assertNull(ownerManufacturerOf(deviceId));

        BizException e = assertThrows(BizException.class,
                () -> inventoryService.stationConsignmentInbound(deviceId, stationId, null),
                "缺货权方时必须失败关闭，不得产生一条无货权方的寄售占有权");
        assertEquals(40943, e.getCode());
        assertEquals("inventory.owner_manufacturer.missing", e.getMessageCode());
        assertEquals(HttpStatus.CONFLICT, e.httpStatus());

        // 失败关闭：一处都不许写
        assertEquals(0, countActiveCustody(deviceId));
        assertEquals("PRODUCING", str("SELECT current_status FROM claw.inventory WHERE device_id = ?", deviceId));
        assertNull(lng("SELECT holder_station_id FROM claw.inventory WHERE device_id = ?", deviceId));
        assertEquals(0, countLifecycleEvents(deviceId, "RECEIVE"));
    }

    @Test
    @DisplayName("40401：设备不在库存台账中（扫码扫到非本平台设备）直接报错")
    void deviceNotInInventoryRejected() {
        String tag = uuid();
        Long stationId = insertStation(tag + "-A");
        // 只有设备行、没有库存台账行
        Long deviceId = insertDeviceWithoutInventory(tag, 1);

        BizException e = assertThrows(BizException.class,
                () -> inventoryService.stationConsignmentInbound(deviceId, stationId, null));
        assertEquals(40401, e.getCode());
        assertEquals("inventory.not.found", e.getMessageCode());
        assertEquals(HttpStatus.NOT_FOUND, e.httpStatus());
        assertEquals(0, countActiveCustody(deviceId));
    }

    @Test
    @DisplayName("Controller 层：请求体缺 deviceId 返回 HTTP 400 / code 10001")
    void missingDeviceIdReturns400() throws Exception {
        String tag = uuid();
        Long myStation = insertStation(tag + "-A");
        Long userId = insertUser(tag);
        bindPrincipal(userId, "STATION", myStation);
        grantInboundPermission(userId);
        setAuth(userId);

        mockMvc.perform(post(INBOUND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    /* ================================================================== */
    /* ⑦ 权限闸：服务站主体但没拿到权限位 → 403（与「不是服务站」两道闸各管各的）*/
    /* ================================================================== */

    @Test
    @DisplayName("权限闸：服务站主体但缺少 station:consignment:inbound 权限位，返回 403 且不落库")
    void stationWithoutPermissionGets403() throws Exception {
        String tag = uuid();
        Long manufacturerId = insertManufacturer(tag);
        Long myStation = insertStation(tag + "-A");
        Long deviceId = insertDevice(tag, manufacturerId, 1);

        Long stationUser = insertUser(tag + "-np");
        // 主体是服务站（作用域能解析出站），但角色里不含寄售入库权限位
        bindPrincipal(stationUser, "STATION", myStation);
        grantBlankRole(stationUser);
        setAuth(stationUser);

        mockMvc.perform(post(INBOUND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":" + deviceId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        // 权限闸在写入之前：不得留下任何痕迹
        assertEquals(0, countActiveCustody(deviceId));
        assertEquals("PRODUCING", str("SELECT current_status FROM claw.inventory WHERE device_id = ?", deviceId));
        assertNull(lng("SELECT holder_station_id FROM claw.inventory WHERE device_id = ?", deviceId));
    }

    /* ================================================================== */
    /* 数据准备（照抄 ConsignmentCustodyIT 的直写 SQL 风格）                 */
    /* ================================================================== */

    private static String uuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long insertManufacturer(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.manufacturers (code, name, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, "MFG-" + tag, "厂家-" + tag);
    }

    /** 建服务站：onboarding_status 必须 ACTIVATED，否则被 40340 org.disabled.readonly 拦下。 */
    private Long insertStation(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.stations (code, name, country_code, status, open_hours, onboarding_status) "
                        + "VALUES (?, ?, 'KHM', 'ACTIVE', '24H', ?) RETURNING id",
                Long.class, "ST-" + tag, "服务站-" + tag, OnboardingStatus.ACTIVATED.name());
    }

    /** 建带授信额度的服务站（授信额度为 NULL 时按 Q18 放行不校验，超限用例必须显式设置）。 */
    private Long insertStationWithCreditLimit(String tag, BigDecimal creditLimit) {
        Long id = insertStation(tag);
        jdbc.update("UPDATE claw.stations SET credit_limit = ? WHERE id = ?", creditLimit, id);
        return id;
    }

    /** 建资产 + 设备 + 库存行（货值快照 100.00）。 */
    private Long insertDevice(String tag, Long manufacturerId, int seq) {
        Long assetId = jdbc.queryForObject(
                "INSERT INTO claw.assets (asset_type, asset_no, status, manufacturer_id) "
                        + "VALUES ('BATTERY', ?, 'IN_STOCK', ?) RETURNING id",
                Long.class, "AST-" + tag + "-" + seq, manufacturerId);
        Long deviceId = jdbc.queryForObject(
                "INSERT INTO claw.devices (asset_id, device_type, status) "
                        + "VALUES (?, 'BATTERY_BMS', 'ACTIVE') RETURNING id",
                Long.class, assetId);
        jdbc.update(
                "INSERT INTO claw.inventory (asset_id, device_id, ownership_type, owner_manufacturer_id, "
                        + "current_status, serial_number, unit_value, value_currency) "
                        + "VALUES (?, ?, 'OWNED_BY_MFG', ?, 'PRODUCING', ?, 100.00, 'USD')",
                assetId, deviceId, manufacturerId, "SN-" + tag + "-" + seq);
        return deviceId;
    }

    /**
     * 给设备写上设备编号（{@code claw.devices.device_no}）—— 扫码枪扫的就是这个值。
     * 不改 {@link #insertDevice} 签名（其它用例都在用），只在需要时补一笔 UPDATE。
     */
    private void setDeviceNo(Long deviceId, String deviceNo) {
        jdbc.update("UPDATE claw.devices SET device_no = ? WHERE id = ?", deviceNo, deviceId);
    }

    /** 建资产 + 设备 + 库存行，但<b>不填货权方</b>（模拟厂家未补全台账，用于 40943）。 */
    private Long insertDeviceWithoutOwner(String tag, int seq) {
        Long assetId = jdbc.queryForObject(
                "INSERT INTO claw.assets (asset_type, asset_no, status) "
                        + "VALUES ('BATTERY', ?, 'IN_STOCK') RETURNING id",
                Long.class, "AST-NO-" + tag + "-" + seq);
        Long deviceId = jdbc.queryForObject(
                "INSERT INTO claw.devices (asset_id, device_type, status) "
                        + "VALUES (?, 'BATTERY_BMS', 'ACTIVE') RETURNING id",
                Long.class, assetId);
        jdbc.update(
                "INSERT INTO claw.inventory (asset_id, device_id, ownership_type, owner_manufacturer_id, "
                        + "current_status, serial_number, unit_value, value_currency) "
                        + "VALUES (?, ?, 'OWNED_BY_MFG', NULL, 'PRODUCING', ?, 100.00, 'USD')",
                assetId, deviceId, "SN-NO-" + tag + "-" + seq);
        return deviceId;
    }

    /** 建资产 + 设备，<b>不建库存台账行</b>（用于 40401）。 */
    private Long insertDeviceWithoutInventory(String tag, int seq) {
        Long assetId = jdbc.queryForObject(
                "INSERT INTO claw.assets (asset_type, asset_no, status) "
                        + "VALUES ('BATTERY', ?, 'IN_STOCK') RETURNING id",
                Long.class, "AST-NI-" + tag + "-" + seq);
        return jdbc.queryForObject(
                "INSERT INTO claw.devices (asset_id, device_type, status) "
                        + "VALUES (?, 'BATTERY_BMS', 'ACTIVE') RETURNING id",
                Long.class, assetId);
    }

    private Long insertUser(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.users (phone, full_name, status, kyc_status, locale, tenant_id) "
                        + "VALUES (?, ?, 'ACTIVE', 'VERIFIED', 'zh', 1) RETURNING id",
                Long.class, "IT-" + tag, "IT用户-" + tag);
    }

    /** 主体绑定：决定「我是哪个站 / 哪个厂家」（InventoryScopeService 据此解析作用域）。 */
    private void bindPrincipal(Long userId, String principalType, Long principalId) {
        jdbc.update("INSERT INTO claw.principal_bindings (user_id, principal_type, principal_id) "
                + "VALUES (?, ?, ?)", userId, principalType, principalId);
    }

    /**
     * 发一个「只含寄售入库权限位」的自定义角色。
     *
     * <p>不复用种角色 STATION：本测试要证明的是「权限位 + 主体类型」两道闸各管各的 ——
     * 厂家主体拿不到站，是因为主体不是站，与权限位无关。roles.grants 自 V18 起是 TEXT。
     */
    private void grantInboundPermission(Long userId) {
        String tag = "IT_ROLE_" + UUID.randomUUID().toString().substring(0, 8);
        Long roleId = jdbc.queryForObject(
                "INSERT INTO claw.roles (code, name_i18n, grants, auto_grant, status, "
                        + "data_scope, data_scope_types, data_rule_ids) "
                        + "VALUES (?, ?, ?, FALSE, 'ACTIVE', 'SELF', '[]', '') RETURNING id",
                Long.class, tag, "IT寄售入库角色", "[\"" + PERMISSION + "\"]");
        grantRole(userId, roleId);
    }

    /**
     * 发一个<b>不含任何权限位</b>的角色：主体类型与权限位是两道独立的闸，
     * 本用例要证明的是「是服务站也没用，没权限位就是 403」。
     */
    private void grantBlankRole(Long userId) {
        String tag = "IT_ROLE_BLANK_" + UUID.randomUUID().toString().substring(0, 8);
        Long roleId = jdbc.queryForObject(
                "INSERT INTO claw.roles (code, name_i18n, grants, auto_grant, status, "
                        + "data_scope, data_scope_types, data_rule_ids) "
                        + "VALUES (?, ?, '[]', FALSE, 'ACTIVE', 'SELF', '[]', '') RETURNING id",
                Long.class, tag, "IT空权限角色");
        grantRole(userId, roleId);
    }

    private void grantRole(Long userId, Long roleId) {
        jdbc.update("INSERT INTO claw.user_roles (user_id, role_id, tenant_id, created_at) "
                + "VALUES (?, ?, 1, now())", userId, roleId);
    }

    private Long requireRoleId(String code) {
        Long roleId = jdbc.queryForObject("SELECT id FROM claw.roles WHERE code = ?", Long.class, code);
        assertNotNull(roleId, "真库上应存在种子角色 " + code);
        return roleId;
    }

    /** 写入登录态（MockMvc 与测试同线程，控制器 AuthContext 能读到）。 */
    private void setAuth(Long userId) {
        ClawUser user = new ClawUser(userId, "IT-" + userId, "STATION");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority(ClawUser.AUTHORITY_USER)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /* ================================================================== */
    /* 断言辅助                                                            */
    /* ================================================================== */

    private List<String> recordComponents() {
        return Arrays.stream(StationRequests.StationConsignmentInbound.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
    }

    private int countActiveCustody(Long deviceId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM claw.consignment_custodies WHERE device_id = ? AND ended_at IS NULL",
                Integer.class, deviceId);
        return n == null ? 0 : n;
    }

    private Long holderStationOf(Long deviceId) {
        return jdbc.queryForObject(
                "SELECT holder_station_id FROM claw.consignment_custodies WHERE device_id = ? AND ended_at IS NULL",
                Long.class, deviceId);
    }

    private Long custodyManufacturerOf(Long deviceId) {
        return jdbc.queryForObject(
                "SELECT manufacturer_id FROM claw.consignment_custodies WHERE device_id = ? AND ended_at IS NULL",
                Long.class, deviceId);
    }

    private String custodyStatusOf(Long deviceId) {
        return jdbc.queryForObject(
                "SELECT status FROM claw.consignment_custodies WHERE device_id = ? AND ended_at IS NULL",
                String.class, deviceId);
    }

    private Long ownerManufacturerOf(Long deviceId) {
        return jdbc.queryForObject(
                "SELECT owner_manufacturer_id FROM claw.inventory WHERE device_id = ?", Long.class, deviceId);
    }

    private String deviceLifecycleStatus(Long deviceId) {
        return jdbc.queryForObject("SELECT lifecycle_status FROM claw.devices WHERE id = ?", String.class, deviceId);
    }

    private int countLifecycleEvents(Long deviceId, String eventType) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM claw.lifecycle_events WHERE device_id = ? AND event_type = ?",
                Integer.class, deviceId, eventType);
        return n == null ? 0 : n;
    }

    private int countCreditBlocks(Long stationId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM claw.onboarding_credit_blocks WHERE principal_id = ?",
                Integer.class, stationId);
        return n == null ? 0 : n;
    }

    /** 取 claw.inventory 上某个字段（用于「按编号入库 vs 按 ID 入库」逐项比对）。 */
    private String inv(Long deviceId, String column) {
        return str("SELECT " + column + " FROM claw.inventory WHERE device_id = ?", deviceId);
    }

    /**
     * 按站点统计行数（用于「失败关闭：一处都不写」断言）。
     * 口径限定在本用例独占的站点上，避免被其它用例的历史数据干扰。
     */
    private int countByStation(String table, String stationColumn, Long stationId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + stationColumn + " = ?", Integer.class, stationId);
        return n == null ? 0 : n;
    }

    private String str(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private Long lng(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
