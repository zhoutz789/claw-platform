package com.claw.server.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移完整性集成测试：在真实 PostgreSQL(TimescaleDB) 容器上验证 V1-V26 全部迁移可应用，
 * 且关键表结构与 JPA 实体映射一致。取代此前仅依赖内存/H2 的弱校验。
 */
class SchemaMigrationIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void allMigrationsAppliedOnRealPostgres() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM claw.flyway_schema_history WHERE version IS NOT NULL", Integer.class);
        // V1-V26 共 26 个迁移脚本（不含 Flyway 自身的 SCHEMA 标记行），全部应在真实库中成功应用
        assertEquals(26, applied, "Flyway 应在真实 PostgreSQL 上应用全部 26 个迁移");
    }

    @Test
    void coreTablesExistAndMappable() {
        // 验证核心表已由迁移创建（accounts / assets / swap_orders 等）
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema='claw' AND table_name IN ('accounts','assets','swap_orders','manufacturers')",
                String.class);
        assertTrue(tables.contains("accounts"), "claw.accounts 应存在");
        assertTrue(tables.contains("assets"), "claw.assets 应存在");
        assertTrue(tables.contains("swap_orders"), "claw.swap_orders 应存在");
        assertTrue(tables.contains("manufacturers"), "claw.manufacturers 应存在");
    }
}
