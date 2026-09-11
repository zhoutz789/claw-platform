SET search_path = claw;

-- telemetry_latest 扩展 BMS 实时遥测列（锂电池 BMS 对接方案 Phase A）。
-- 每电芯/温度探头先用 JSONB（risk #5：先 JSONB，验证后再拆 battery_cell_telemetry 表）。
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS pack_voltage NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS current_a NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS power_w NUMERIC(11,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ccl NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dcl NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS cvl NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS remaining_capacity_ah NUMERIC(10,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS full_charge_capacity_ah NUMERIC(10,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS temp_max NUMERIC(6,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS temp_min NUMERIC(6,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS temp_max_id INT;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS temp_min_id INT;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS temperatures_json jsonb;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS cell_voltages_json jsonb;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS balance_status VARCHAR(16);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS balance_current NUMERIC(8,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS cell_voltage_spread NUMERIC(7,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS charge_enable BOOLEAN;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS discharge_enable BOOLEAN;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS heater_enable BOOLEAN;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS water_cooling_enable BOOLEAN;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS fan_speed INT;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS coolant_temp_in NUMERIC(6,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS coolant_temp_out NUMERIC(6,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS satellite_count INT;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS last_fix_time TIMESTAMP;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS bms_state VARCHAR(16);

-- telemetry 与 telemetry_latest 口径对齐（一致性修复，风险 #2/#4）。
ALTER TABLE claw.telemetry ADD COLUMN IF NOT EXISTS soc NUMERIC(5,2);
ALTER TABLE claw.telemetry ADD COLUMN IF NOT EXISTS temp NUMERIC(6,2);
