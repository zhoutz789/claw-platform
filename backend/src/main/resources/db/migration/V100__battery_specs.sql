SET search_path = claw;

-- 电池组级规格字段（锂电池 BMS 对接方案 Phase A/B，2026-09-11）。
-- 实例级差异（同型号不同批次）以列为主、products.params_json 兜底。
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS chemistry VARCHAR(16);
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS nominal_voltage NUMERIC(8,2);
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS capacity_ah NUMERIC(10,2);
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS cell_series INT;
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS cell_parallel INT;
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS cell_config VARCHAR(32);
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS rated_power_w NUMERIC(10,2);
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS max_charge_current_a NUMERIC(8,2);
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS max_discharge_current_a NUMERIC(8,2);
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS charge_voltage_limit NUMERIC(8,2);
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS discharge_voltage_limit NUMERIC(8,2);
ALTER TABLE claw.batteries ADD COLUMN IF NOT EXISTS temp_probe_count INT;
