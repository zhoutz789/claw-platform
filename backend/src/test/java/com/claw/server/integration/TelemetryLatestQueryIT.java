package com.claw.server.integration;

import com.claw.server.domain.iot.TelemetryLatest;
import com.claw.server.domain.iot.TelemetryLatestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最新遥测查询 × 真实 PostgreSQL 集成测试（缺陷真根因证明）。
 *
 * <p>缺陷现象：{@code GET /api/v1/assets/{id}/telemetry} → HTTP 500。根因是仓储方法
 * {@code Optional<TelemetryLatest> findByAssetId(Long)} 假设"一个资产仅一行"，而真实库中
 * {@code claw.telemetry_latest} 仅在 {@code device_id} 上有唯一约束，{@code asset_id} 无唯一约束——
 * 一个资产可挂多台设备，每台设备各有一条最新记录。多行返回单个 {@code Optional} 时 Spring Data /
 * Hibernate 抛 {@code NonUniqueResultException}，被全局兜底成 500。
 *
 * <p>本测试在<b>真实 PG（TimescaleDB claw_it）</b>上落库同一 {@code asset_id}、不同 {@code device_id}、
 * 不同 {@code reported_at} 的多行数据，断言新方法 {@code findTopByAssetIdOrderByReportedAtDescIdDesc}
 * 稳定取回报文时间最新的一条；并覆盖同刻 {@code id} 兜底与不存在资产的空返回。
 *
 * <p>注意：旧方法 {@code findByAssetId} 已从仓储删除（由编译器保证不再被误用），故本测试无法直接断言
 * 旧方法抛异常——改用"同一 assetId 下真实存在多行"这一前提 + 新方法返回 Top1 来锁定正确行为。
 */
class TelemetryLatestQueryIT extends AbstractIntegrationTest {

    @Autowired
    TelemetryLatestRepository telemetryLatestRepository;

    @Autowired
    JdbcTemplate jdbc;

    /** 本测试创建的资产 id，用于 @AfterEach 清理，保证可重复运行。 */
    private final List<Long> createdAssetIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long assetId : createdAssetIds) {
            // 先删引用方（telemetry_latest / devices），再删被引用方（assets），规避外键。
            jdbc.update("DELETE FROM claw.telemetry_latest WHERE asset_id = ?", assetId);
            jdbc.update("DELETE FROM claw.devices WHERE asset_id = ?", assetId);
            jdbc.update("DELETE FROM claw.assets WHERE id = ?", assetId);
        }
        createdAssetIds.clear();
    }

    @Test
    void findTopByAssetId_returnsMostRecentAcrossAssetDevices_onRealPostgres() {
        String suffix = randomSuffix();
        long assetId = createAsset(suffix);

        Instant t1 = Instant.parse("2024-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2024-01-01T12:00:00Z");
        Instant t3 = Instant.parse("2024-01-01T15:00:00Z");

        long d1 = createDevice(assetId, suffix + "-1");
        long d2 = createDevice(assetId, suffix + "-2");
        long d3 = createDevice(assetId, suffix + "-3");

        // 同一资产 3 台设备，各一条最新记录；d2 的上报时间最新。
        insertTelemetry(d1, assetId, t1, "10.00");
        insertTelemetry(d2, assetId, t3, "30.00");
        insertTelemetry(d3, assetId, t2, "20.00");

        // 前置事实：真实库中该资产确有 3 行（这正是旧 findByAssetId 会炸的数据形态）。
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM claw.telemetry_latest WHERE asset_id = ?", Integer.class, assetId);
        assertEquals(3, rows, "该资产应在真实库中有 3 条最新遥测（多设备各一条）");

        Optional<TelemetryLatest> top =
                telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(assetId);

        assertTrue(top.isPresent(), "应返回该资产最新一条遥测");
        assertEquals(d2, top.get().getDeviceId(), "应返回 reported_at 最大的设备记录");
        assertEquals(t3, top.get().getReportedAt(), "返回记录的 reported_at 应为最大时间");
        assertEquals(0, new BigDecimal("30.00").compareTo(top.get().getSoc()), "返回记录的 soc 应来自最新那条");
    }

    @Test
    void findTopByAssetId_tieBreaksByIdDesc_whenSameReportedAt() {
        String suffix = randomSuffix();
        long assetId = createAsset(suffix);

        Instant sameAt = Instant.parse("2024-02-01T08:00:00Z");
        long d1 = createDevice(assetId, suffix + "-a");
        long d2 = createDevice(assetId, suffix + "-b");

        // 两条报告时间完全相同：应按 id 降序兜底，取后插入者，保证确定性。
        long firstId = insertTelemetry(d1, assetId, sameAt, "41.00");
        long secondId = insertTelemetry(d2, assetId, sameAt, "42.00");
        assertTrue(secondId > firstId, "自增 id 应递增");

        Optional<TelemetryLatest> top =
                telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(assetId);

        assertTrue(top.isPresent());
        assertEquals(d2, top.get().getDeviceId(), "同刻应取 id 更大的记录（确定性兜底）");
        assertEquals(0, new BigDecimal("42.00").compareTo(top.get().getSoc()));
    }

    @Test
    void findTopByAssetId_returnsEmpty_whenAssetHasNoTelemetry() {
        Optional<TelemetryLatest> top =
                telemetryLatestRepository.findTopByAssetIdOrderByReportedAtDescIdDesc(-1L);
        assertTrue(top.isEmpty(), "不存在/无数据的资产应返回空 Optional，而非抛异常");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private long createAsset(String suffix) {
        Long id = jdbc.queryForObject(
                "INSERT INTO claw.assets(asset_type, asset_no) VALUES ('vehicle', ?) RETURNING id",
                Long.class, "IT-TLM-" + suffix);
        long assetId = id == null ? 0L : id;
        createdAssetIds.add(assetId);
        return assetId;
    }

    private long createDevice(long assetId, String tag) {
        Long id = jdbc.queryForObject(
                "INSERT INTO claw.devices(asset_id, device_type) VALUES (?, 'VEHICLE_TCU') RETURNING id",
                Long.class, assetId);
        return id == null ? 0L : id;
    }

    private long insertTelemetry(long deviceId, long assetId, Instant reportedAt, String soc) {
        Long id = jdbc.queryForObject(
                "INSERT INTO claw.telemetry_latest(device_id, asset_id, soc, reported_at) " +
                        "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, deviceId, assetId, new BigDecimal(soc),
                OffsetDateTime.ofInstant(reportedAt, ZoneOffset.UTC));
        return id == null ? 0L : id;
    }
}
