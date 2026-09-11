SET search_path = claw;

-- telemetry_latest 扩展光伏（PV）实时遥测列（光伏数据链路切片）。
-- 单位铁律（与 PvTelemetryReport / PvAdapter 一致）：电压 V、电流 A、功率 W、电量 Wh、
-- 温度 ℃、辐照度 W/m²、日辐照量 kWh/m²、频率 Hz、百分比 %、功率因数/效率 无量纲。
-- 全部列可空：不同厂家/机型上报字段集不同，缺失字段保持 null，落库时跳过（不覆盖为 null）。

-- —— 交流侧（逆变器并网输出）——
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ac_voltage_a NUMERIC(8,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ac_voltage_b NUMERIC(8,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ac_voltage_c NUMERIC(8,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ac_current_a NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ac_current_b NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ac_current_c NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ac_frequency NUMERIC(6,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ac_active_power_w NUMERIC(11,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ac_reactive_power_var NUMERIC(11,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS power_factor NUMERIC(5,4);

-- —— 直流侧（最多 4 路 MPPT）——
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_voltage_1 NUMERIC(8,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_voltage_2 NUMERIC(8,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_voltage_3 NUMERIC(8,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_voltage_4 NUMERIC(8,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_current_1 NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_current_2 NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_current_3 NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_current_4 NUMERIC(9,3);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_power_w_1 NUMERIC(11,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_power_w_2 NUMERIC(11,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_power_w_3 NUMERIC(11,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS dc_power_w_4 NUMERIC(11,2);

-- —— 组串电流数组（路数不定，先 JSON；与 temperatures_json / cell_voltages_json 同策略）——
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS string_currents_json JSON;

-- —— 并网点电表（双向计量：forward=上网/馈网，reverse=下网/购电）——
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS meter_active_power_w NUMERIC(11,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS forward_total_wh NUMERIC(18,4);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS reverse_total_wh NUMERIC(18,4);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS demand_w NUMERIC(11,2);

-- —— 气象站（用于 PR 与功率异常判定）——
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS irradiance NUMERIC(8,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS module_temp NUMERIC(6,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS ambient_temp NUMERIC(6,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS wind_speed NUMERIC(6,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS daily_irradiation NUMERIC(10,4);

-- —— 发电量（累计计数器：小时电量只由它的差分得到，绝不用功率积分）——
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS daily_yield_wh NUMERIC(18,4);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS total_yield_wh NUMERIC(18,4);

-- —— 运行状态 ——
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS inverter_state VARCHAR(24);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS fault_code INTEGER;
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS derate_percent NUMERIC(5,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS internal_temp NUMERIC(6,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS heatsink_temp NUMERIC(6,2);
ALTER TABLE claw.telemetry_latest ADD COLUMN IF NOT EXISTS efficiency NUMERIC(5,4);
