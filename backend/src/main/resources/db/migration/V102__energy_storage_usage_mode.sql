SET search_path = claw;

-- 资产使用模式（锂电池 BMS 对接方案 Phase D，2026-09-11）。
-- SWAP=换电市场（默认）；STORAGE=被能源调度临时借调为储能（usage mode 切换，非资产类型变更）。
-- 与 asset_type 解耦：ENERGY_STORAGE 资产恒为 STORAGE，换电 BATTERY 可临时切换为 STORAGE。
ALTER TABLE claw.assets ADD COLUMN IF NOT EXISTS usage_mode VARCHAR(16) DEFAULT 'SWAP';
