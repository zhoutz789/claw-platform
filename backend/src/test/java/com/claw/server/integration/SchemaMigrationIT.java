package com.claw.server.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移完整性集成测试：在真实 PostgreSQL(TimescaleDB) 容器上验证全部迁移可应用，
 * 且关键表结构与 JPA 实体映射一致。取代此前仅依赖内存/H2 的弱校验。
 */
class SchemaMigrationIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void allMigrationsAppliedOnRealPostgres() throws IOException {
        int expected = countMigrationScripts();
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM claw.flyway_schema_history WHERE version IS NOT NULL", Integer.class);
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM claw.flyway_schema_history WHERE success = false", Integer.class);

        // 期望值不再硬编码（历史上此处写死 38，新增迁移后必然误报）：
        // 直接扫 classpath 下 db/migration/V*.sql 的脚本数，增删迁移无需改测试。
        // version IS NULL 的行是 Flyway 自身的 SCHEMA 标记行，不计入。
        assertEquals(0, failed, "Flyway 不应存在失败的迁移");
        assertTrue(applied != null && applied > 0, "真实库上应至少应用 1 个迁移（Flyway 未生效？）");
        assertEquals(expected, applied,
                "Flyway 应在真实 PostgreSQL 上应用 db/migration 下的全部迁移脚本（当前共 " + expected + " 个）");
    }

    /** 统计 classpath 下的版本化迁移脚本数（V*.sql，不含可重复迁移 R__*.sql）。 */
    private static int countMigrationScripts() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");
        long count = Arrays.stream(scripts).filter(Resource::isReadable).count();
        assertTrue(count > 0, "classpath 下应能扫到 db/migration/V*.sql 迁移脚本");
        return (int) count;
    }

    @Test
    void coreTablesExistAndMappable() {
        // 验证核心表已由迁移创建（accounts / assets / swap_orders / 项目管理域 等）
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema='claw' AND table_name IN (" +
                "'accounts','assets','swap_orders','manufacturers','products'," +
                "'projects','project_devices','device_authorizations')",
                String.class);
        assertTrue(tables.contains("accounts"), "claw.accounts 应存在");
        assertTrue(tables.contains("assets"), "claw.assets 应存在");
        assertTrue(tables.contains("swap_orders"), "claw.swap_orders 应存在");
        assertTrue(tables.contains("manufacturers"), "claw.manufacturers 应存在");
        assertTrue(tables.contains("products"), "claw.products 应存在");
        assertTrue(tables.contains("projects"), "claw.projects 应存在（V36）");
        assertTrue(tables.contains("project_devices"), "claw.project_devices 应存在（V36）");
        assertTrue(tables.contains("device_authorizations"), "claw.device_authorizations 应存在（V36）");
    }
}
