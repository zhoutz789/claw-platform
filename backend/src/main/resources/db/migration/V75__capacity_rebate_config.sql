-- =====================================================================
-- Claw 平台 V75 增量（容量预订回佣率配置默认值 + 上限）
-- 依据：缺口⑤（容量预订回佣率全局默认 + 上限，配置驱动）
-- 注意：V60+ 迁移已应用且不可变；本文件为新增 V75，使用 idempotent 写法，
--       重跑安全（WHERE NOT EXISTS 守卫）。
-- 列：claw.system_config(config_key, config_value, category, description,
--      data_type, editable, tenant_id, created_at, updated_at, deleted)
-- =====================================================================

INSERT INTO claw.system_config (config_key, config_value, category, description, data_type, editable, tenant_id, created_at, updated_at, deleted)
SELECT 'CAPACITY_REBATE_RATE_DEFAULT', '0.10', 'CAPACITY', '容量预订默认回佣率', 'NUMBER', true, 1, now(), now(), false
WHERE NOT EXISTS (SELECT 1 FROM claw.system_config WHERE config_key = 'CAPACITY_REBATE_RATE_DEFAULT');

INSERT INTO claw.system_config (config_key, config_value, category, description, data_type, editable, tenant_id, created_at, updated_at, deleted)
SELECT 'CAPACITY_REBATE_RATE_MAX', '0.30', 'CAPACITY', '容量预订回佣率上限', 'NUMBER', true, 1, now(), now(), false
WHERE NOT EXISTS (SELECT 1 FROM claw.system_config WHERE config_key = 'CAPACITY_REBATE_RATE_MAX');
