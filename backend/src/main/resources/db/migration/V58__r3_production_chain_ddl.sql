-- =====================================================================
-- Claw 平台 V58 增量（R3 生产入库链路 DDL 补漏）
--
-- 背景（P0 缺陷 · 会阻断 R3 生产入库）：
--   R3「无库存时走生产管理 → 基于 product 实例化 device（序列号+合格证）→ 入厂家库存」
--   这条链路上有三处 DDL 在 V1–V57 **从未建过**，之所以一直没暴露，是因为
--   application-local.yml 走 H2 + ddl-auto: update（Hibernate 按实体自动建表/加列），
--   而真实 PostgreSQL 环境（application.yml）是 ddl-auto: none + Flyway 管 schema，
--   缺什么就硬报错：
--
--   1) claw.production_tasks 整表缺失
--      → ProductionService.createTask() 建单即报
--        relation "claw.production_tasks" does not exist
--
--   2) claw.devices.lifecycle_status 列缺失（R4 冗余状态字段，实体 Device.lifecycleStatus）
--      → completeTask() 建 devices 行时报
--        column "lifecycle_status" of relation "devices" does not exist
--
--   3) claw.devices.product_id 列缺失（R3 实例化来源商品，实体 Device.productId）
--      → 同上，devices 行写不进「由哪个商品实例化」的溯源
--
-- 修复：按项目既有约定（Flyway 独占 schema）补齐上述 DDL，列与实体一一对应。
--   production_tasks 的主键用 BIGSERIAL 对应实体的 GenerationType.IDENTITY。
--
-- 全量幂等：CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS。
-- =====================================================================

SET search_path = claw;

-- ---------------------------------------------------------------------
-- 1) 生产任务表（基于 product 实例化 device 的批次：CREATED → PRODUCING → DONE）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS production_tasks (
    id                 BIGSERIAL    PRIMARY KEY,
    manufacturer_id    BIGINT       NOT NULL REFERENCES manufacturers (id),   -- 生产厂家
    product_id         BIGINT       NOT NULL REFERENCES products (id),        -- 实例化来源商品
    plan_quantity      INT          NOT NULL DEFAULT 0,                       -- 计划产量
    produced_quantity  INT          NOT NULL DEFAULT 0,                       -- 实际完工产量
    status             VARCHAR(20)  NOT NULL DEFAULT 'CREATED',               -- CREATED / PRODUCING / DONE
    spec_json          JSONB,                                                 -- 批次规格快照（选配参数）
    created_by         BIGINT,                                                -- 建单人（登录用户 id）
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  production_tasks                IS '生产任务（V58 · R3）：无库存时按 product 实例化 device 的批次载体。';
COMMENT ON COLUMN production_tasks.product_id     IS '实例化来源商品，决定产出设备的 asset_type / device_type';
COMMENT ON COLUMN production_tasks.status         IS '批次状态：CREATED 建单 / PRODUCING 生产中 / DONE 已完工入库';

-- ---------------------------------------------------------------------
-- 2) devices 补齐 R3/R4 两个实体字段
-- ---------------------------------------------------------------------
-- R4：设备生命周期状态冗余（权威历史在 lifecycle_events，便于列表筛选）
ALTER TABLE claw.devices ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(20);
-- R3：实例化来源商品（生产入库溯源：这批设备由哪个 product 产出）
ALTER TABLE claw.devices ADD COLUMN IF NOT EXISTS product_id BIGINT REFERENCES claw.products (id);

COMMENT ON COLUMN claw.devices.lifecycle_status IS 'R4 冗余状态（PRODUCING/IN_STOCK…），权威历史见 claw.lifecycle_events';
COMMENT ON COLUMN claw.devices.product_id       IS 'R3 实例化来源商品，生产入库溯源';

-- ---------------------------------------------------------------------
-- 3) 索引（全部 IF NOT EXISTS，重复执行 no-op）
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_pt_manufacturer  ON production_tasks (manufacturer_id);
CREATE INDEX IF NOT EXISTS idx_pt_product       ON production_tasks (product_id);
CREATE INDEX IF NOT EXISTS idx_pt_status        ON production_tasks (status);
CREATE INDEX IF NOT EXISTS idx_devices_product  ON claw.devices (product_id);
CREATE INDEX IF NOT EXISTS idx_devices_lifecycle ON claw.devices (lifecycle_status);
