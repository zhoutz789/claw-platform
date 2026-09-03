package com.claw.server.domain.inventory;

import com.claw.server.common.dto.InventoryViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存统计只读聚合（模块三 · M3-4）。
 *
 * <p>作用域有「全平台 / 厂家 / 站点」3 种 × 5 个聚合维度 = 15 种组合。
 * 走 {@link JdbcClient} 手写 SQL 聚合（工程已有先例 {@code OrgWritableGuard.find()} 读治理视图），
 * 而非把全表捞进内存做 Stream 分组（管理员视角是全平台量级，Stream 分组会 OOM/超时）。
 * 同一段 SQL 骨架 + 动态 WHERE 片段覆盖全部组合。
 *
 * 全部方法只读事务（A.5 硬约束）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryStatsQuery {

    private final JdbcClient jdbcClient;

    /** 按所有权类型聚合（OWNED_BY_MFG / CONSIGNED / FULL）。 */
    @Transactional(readOnly = true)
    public List<InventoryViews.OwnershipCount> countByOwnership(Long manufacturerId, Long stationId) {
        return jdbcClient.sql(buildSql(
                        "SELECT ownership_type, count(*) AS cnt FROM claw.inventory", manufacturerId, stationId,
                        "GROUP BY ownership_type"))
                .params(buildParams(manufacturerId, stationId))
                .query((rs, rn) -> new InventoryViews.OwnershipCount(rs.getString("ownership_type"), rs.getLong("cnt")))
                .list();
    }

    /** 按生命周期状态聚合。 */
    @Transactional(readOnly = true)
    public List<InventoryViews.StatusCount> countByStatus(Long manufacturerId, Long stationId) {
        return jdbcClient.sql(buildSql(
                        "SELECT current_status, count(*) AS cnt FROM claw.inventory", manufacturerId, stationId,
                        "GROUP BY current_status"))
                .params(buildParams(manufacturerId, stationId))
                .query((rs, rn) -> new InventoryViews.StatusCount(rs.getString("current_status"), rs.getLong("cnt")))
                .list();
    }

    /** 按服务站聚合（仅非 null 站点，倒序 Top 50）。manufacturerId / stationId 可选收窄。 */
    @Transactional(readOnly = true)
    public List<InventoryViews.StationCount> countByStation(Long manufacturerId, Long stationId) {
        StringBuilder sb = new StringBuilder(
                "SELECT holder_station_id, count(*) AS cnt FROM claw.inventory WHERE holder_station_id IS NOT NULL ");
        if (manufacturerId != null) sb.append("AND owner_manufacturer_id = :mfg ");
        if (stationId != null) sb.append("AND holder_station_id = :station ");
        sb.append("GROUP BY holder_station_id ORDER BY 2 DESC LIMIT 50");
        JdbcClient.StatementSpec q = jdbcClient.sql(sb.toString());
        if (manufacturerId != null) q = q.param("mfg", manufacturerId);
        if (stationId != null) q = q.param("station", stationId);
        return q.query((rs, rn) -> new InventoryViews.StationCount(rs.getLong("holder_station_id"), rs.getLong("cnt")))
                .list();
    }

    /** 按厂家聚合（仅非 null 厂家，倒序 Top 50）。manufacturerId / stationId 可选收窄。 */
    @Transactional(readOnly = true)
    public List<InventoryViews.ManufacturerCount> countByManufacturer(Long manufacturerId, Long stationId) {
        StringBuilder sb = new StringBuilder(
                "SELECT owner_manufacturer_id, count(*) AS cnt FROM claw.inventory WHERE owner_manufacturer_id IS NOT NULL ");
        if (stationId != null) sb.append("AND holder_station_id = :station ");
        if (manufacturerId != null) sb.append("AND owner_manufacturer_id = :mfg ");
        sb.append("GROUP BY owner_manufacturer_id ORDER BY 2 DESC LIMIT 50");
        JdbcClient.StatementSpec q = jdbcClient.sql(sb.toString());
        if (stationId != null) q = q.param("station", stationId);
        if (manufacturerId != null) q = q.param("mfg", manufacturerId);
        return q.query((rs, rn) -> new InventoryViews.ManufacturerCount(
                        rs.getLong("owner_manufacturer_id"), rs.getLong("cnt")))
                .list();
    }

    /** 在厂未下发数（holder_station_id IS NULL）。 */
    @Transactional(readOnly = true)
    public long countAtFactory(Long manufacturerId, Long stationId) {
        StringBuilder sb = new StringBuilder(
                "SELECT count(*) AS cnt FROM claw.inventory WHERE holder_station_id IS NULL ");
        if (manufacturerId != null) sb.append("AND owner_manufacturer_id = :mfg ");
        if (stationId != null) sb.append("AND holder_station_id = :station ");
        JdbcClient.StatementSpec q = jdbcClient.sql(sb.toString());
        if (manufacturerId != null) q = q.param("mfg", manufacturerId);
        if (stationId != null) q = q.param("station", stationId);
        return q.query((rs, rn) -> rs.getLong("cnt")).optional().orElse(0L);
    }

    // ---- SQL 片段工具：统一拼 WHERE（mfg / station 可空）----

    private String buildSql(String select, Long manufacturerId, Long stationId, String tail) {
        StringBuilder sb = new StringBuilder(select).append(" WHERE 1=1 ");
        if (manufacturerId != null) sb.append("AND owner_manufacturer_id = :mfg ");
        if (stationId != null) sb.append("AND holder_station_id = :station ");
        if (tail != null && !tail.isEmpty()) sb.append(tail).append(" ");
        return sb.toString();
    }

    private Map<String, Object> buildParams(Long manufacturerId, Long stationId) {
        Map<String, Object> p = new HashMap<>();
        if (manufacturerId != null) p.put("mfg", manufacturerId);
        if (stationId != null) p.put("station", stationId);
        return p;
    }
}
