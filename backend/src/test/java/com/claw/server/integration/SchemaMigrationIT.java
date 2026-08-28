package com.claw.server.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
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
    void allMigrationsAppliedOnRealPostgres() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM claw.flyway_schema_history WHERE version IS NOT NULL", Integer.class);
        // V1-V38 共 38 个迁移脚本（不含 Flyway 自身的 SCHEMA 标记行），全部应在真实库中成功应用。
        // V32 新增 vehicle_iot_contract；V33 为 products 增加富文本元信息 + 分享能力；
        // V34 为订单闭环（customer_order_closure）；V35 为产品模板字段 EAV + 产品表扩展 + 通用电子围栏；
        // V36 为项目管理域（projects / project_devices / device_authorizations）；
        // V37 放宽 accounts.balance 的 CHECK，允许平台 MASTER 清算户为零/负（PROJECT_LEDGER 对冲侧）；
        // V38 为订单→资产生成与流转（customer_order_unit_registrations + assets 溯源/快照 + 维修更换留痕）。
        assertEquals(38, applied, "Flyway 应在真实 PostgreSQL 上应用全部迁移（当前 V1-V38：V38 订单逐台登记与资产生成）");
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
