package com.claw.server.integration;

import com.claw.server.common.enums.OnboardingStatus;
import com.claw.server.domain.inventory.InventoryService;
import com.claw.server.domain.transfer.TransferOrder;
import com.claw.server.domain.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 寄售占有权（claw.consignment_custodies）真库回归（V63 补建表）。
 *
 * <h2>背景</h2>
 * V1–V62 全量迁移中从未创建过 {@code claw.consignment_custodies}，本地 H2（ddl-auto: update）
 * 由 Hibernate 自动建表掩盖了该洞；真库（ddl-auto: none + Flyway）在「铺货入站」时硬报
 * {@code relation "claw.consignment_custodies" does not exist}。
 *
 * <h2>本测试守住的两条放行路径</h2>
 * <ol>
 *   <li>{@link InventoryService#shipToStationBatch} —— 厂家发货到服务站，入寄售库（C1 额度放行分支）；</li>
 *   <li>{@link TransferService#receive} —— 站间调拨收货，关旧占有权 + 开新占有权（C2 额度放行分支）。</li>
 * </ol>
 *
 * <p>跑在真实 PostgreSQL 上（见 {@link AbstractIntegrationTest}），不是 H2。
 */
class ConsignmentCustodyIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InventoryService inventoryService;

    @Autowired
    TransferService transferService;

    /** 迁移必须建出这张表——缺表正是本次修复的阻断缺陷。 */
    @Test
    void tableExistsOnRealPostgres() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'claw' AND table_name = 'consignment_custodies'",
                Integer.class);
        assertEquals(1, n, "claw.consignment_custodies 应由 V63 建出");
    }

    /** 放行路径：铺货入站 → 站间调拨收货，占有权在真库上真正落库。 */
    @Test
    void shipToStationAndTransferReceivePersistCustody() {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        Long manufacturerId = insertManufacturer(tag);
        Long fromStationId = insertStation(tag + "-A");
        Long toStationId = insertStation(tag + "-B");
        Long device1 = insertDevice(tag, manufacturerId, 1);
        Long device2 = insertDevice(tag, manufacturerId, 2);

        // ---- ① 铺货入站（C1 放行分支）----
        inventoryService.shipToStationBatch(List.of(device1, device2), fromStationId, manufacturerId, null);

        assertEquals(2, countActiveCustody(device1, device2), "两台设备应各有一条未结束的占有权");
        assertEquals(fromStationId, holderStationOf(device1), "入站后占有权持有站应为源站");
        assertEquals("ACTIVE", statusOf(device1));

        // ---- ② 站间调拨：建单 → 源站交接 → 目标站收货（C2 放行分支）----
        TransferOrder order = transferService.createTransfer(manufacturerId, fromStationId, toStationId,
                List.of(device1, device2), BigDecimal.ZERO, null);
        assertNotNull(order.getId(), "调拨单应落库并拿到 ID");

        transferService.handover(order.getId(), null);
        assertEquals("TRANSFERRED_OUT", statusOf(device1), "交接后源占有权应置为已转出");

        TransferOrder received = transferService.receive(order.getId(), null);
        assertEquals("COMPLETED", received.getStatus().name(), "收货后调拨单应完成");

        // ---- ③ 收货后：旧占有权关闭留痕，新占有权接管目标站 ----
        assertEquals(4, countCustodyRows(device1, device2), "每台设备应留下 2 行（1 关 1 开）");
        assertEquals(2, countActiveCustody(device1, device2),
                "部分唯一索引 uq_cc_device_active 应保证同时只有 1 条未结束占有权");
        assertEquals(toStationId, holderStationOf(device1), "收货后占有权持有站应为目标站");
        assertEquals("ACTIVE", statusOf(device1));

        Integer ended = jdbc.queryForObject(
                "SELECT count(*) FROM claw.consignment_custodies "
                        + "WHERE device_id IN (?, ?) AND ended_at IS NOT NULL AND ended_reason = 'TRANSFERRED'",
                Integer.class, device1, device2);
        assertEquals(2, ended, "调拨转出的旧占有权应写 ended_reason='TRANSFERRED' 留痕");

        // ---- ④ 二次入站：调拨过的设备可再次铺货，仍只留 1 条未结束占有权 ----
        inventoryService.shipToStationBatch(List.of(device1), fromStationId, manufacturerId, null);
        assertEquals(2, countActiveCustody(device1, device2),
                "二次入站后仍应只有 1 条未结束占有权（历史行不参与当前口径）");
        assertEquals(fromStationId, holderStationOf(device1), "二次入站后持有站应切回源站");
    }

    /**
     * 本轮缺陷的正面回归：<b>V60 回填的历史服务站必须能正常入站</b>。
     *
     * <p>冒烟时的表现是历史服务站入站被 {@code 40340 org.disabled.readonly} 拦下。
     * 根因是入驻治理的激活态在库里存在 {@code 'ACTIVE'}（V60 回填历史站）与
     * {@code 'ACTIVATED'}（新入驻激活）两个字面量，写入守卫 {@code OrgWritableGuard}
     * 只认其中一个，另一批主体就整体被误判为「不可写」。
     * V64 已把存量 {@code 'ACTIVE'} 统一回填为 {@code 'ACTIVATED'}。
     *
     * <p>「历史服务站」的判据：{@code onboarding_application_id IS NULL} —— 从未走入驻流程，
     * 是 V1 种子建站、再由 V60 回填入驻状态的那批行。
     */
    @Test
    void historicalBackfilledStationCanAcceptInbound() {
        Long stationId = jdbc.queryForObject(
                "SELECT id FROM claw.stations "
                        + "WHERE onboarding_application_id IS NULL AND deleted = FALSE ORDER BY id LIMIT 1",
                Long.class);
        assertNotNull(stationId, "真库上应存在 V60 回填的历史服务站（未走入驻流程的存量站）");

        String backfilled = jdbc.queryForObject(
                "SELECT onboarding_status FROM claw.stations WHERE id = ?", String.class, stationId);
        assertEquals(OnboardingStatus.ACTIVATED.name(), backfilled,
                "V64 之后历史服务站的入驻状态必须统一为 ACTIVATED，不能还是 V60 回填的 ACTIVE");

        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long manufacturerId = insertManufacturer(tag);
        Long device = insertDevice(tag, manufacturerId, 1);

        // 守卫放行：历史服务站入站不再抛 40340 org.disabled.readonly
        inventoryService.shipToStationBatch(List.of(device), stationId, manufacturerId, null);

        assertEquals(stationId, holderStationOf(device), "历史服务站入站后应持有该设备的占有权");
        assertEquals("ACTIVE", statusOf(device));
    }

    /* ------------------------------------------------------------------ */
    /* 数据准备：直接写 SQL，避免依赖尚未打通的上游域接口                    */
    /* ------------------------------------------------------------------ */

    private Long insertManufacturer(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.manufacturers (code, name, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, "MFG-" + tag, "厂家-" + tag);
    }

    /**
     * 建服务站。{@code onboarding_status} 必须是 {@code ACTIVATED} —— OrgWritableGuard 只认这个值。
     *
     * <p>注意两个 {@code ACTIVE} 陷阱：
     * <ul>
     *   <li>{@code stations.status='ACTIVE'} 是<b>运营状态</b>（营业中），本方法照样要写；</li>
     *   <li>{@code onboarding_status} 的激活态叫 {@code ACTIVATED}（与入驻申请单终态同名），
     *       写错成 {@code 'ACTIVE'} 会被 40340 org.disabled.readonly 拦下入站。</li>
     * </ul>
     * V64 之前这两者曾并存（历史服务站被回填成 {@code 'ACTIVE'}），本测试用的正是历史站写法，
     * 用来守住「存量历史站能正常入站」这条回归。
     */
    private Long insertStation(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO claw.stations (code, name, country_code, status, open_hours, onboarding_status) "
                        + "VALUES (?, ?, 'KHM', 'ACTIVE', '24H', ?) RETURNING id",
                Long.class, "ST-" + tag, "服务站-" + tag, OnboardingStatus.ACTIVATED.name());
    }

    /** 建资产 + 设备 + 库存行（货值快照 100.00，供 C1/C2 额度校验取数）。 */
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

    /* ------------------------------------------------------------------ */
    /* 断言辅助                                                            */
    /* ------------------------------------------------------------------ */

    private int countCustodyRows(Long d1, Long d2) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM claw.consignment_custodies WHERE device_id IN (?, ?)",
                Integer.class, d1, d2);
        return n == null ? 0 : n;
    }

    private int countActiveCustody(Long d1, Long d2) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM claw.consignment_custodies WHERE device_id IN (?, ?) AND ended_at IS NULL",
                Integer.class, d1, d2);
        return n == null ? 0 : n;
    }

    private String statusOf(Long deviceId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM claw.consignment_custodies WHERE device_id = ? AND ended_at IS NULL",
                String.class, deviceId);
        assertEquals(1, rows.size(), "设备 " + deviceId + " 应有且仅有一条未结束占有权");
        return rows.get(0);
    }

    private Long holderStationOf(Long deviceId) {
        Long stationId = jdbc.queryForObject(
                "SELECT holder_station_id FROM claw.consignment_custodies "
                        + "WHERE device_id = ? AND ended_at IS NULL",
                Long.class, deviceId);
        assertTrue(stationId != null, "未结束占有权应有持有站");
        return stationId;
    }
}
