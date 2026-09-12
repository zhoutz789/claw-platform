SET search_path = claw;

-- TOU 峰谷电价时段（VPP 切片第二批）——给虚拟电厂装上价格信号。
--
-- 设计要点：
--   * start_minute / end_minute 为「当日 0 点起的分钟数」（0–1439），跨零点时段允许
--     start_minute > end_minute（服务层按跨天解释），避免用 TIME 类型带来的时区歧义。
--   * energy_price  = 电度电价 $/kWh（驱动「自用 vs 上网」「储放 vs 市电」）。
--   * demand_price  = 需量电价 $/kW（柬埔寨工商业按最大需量计费，需量管理的钱在这里）。
--   * priority      = 时段重叠时取 priority 小者（同优先级取先入库的）。
--
-- ⚠️ 下方种子数据的电价数值为【占位符，待业务确认，勿直接用于结算】。
--    没有真实电价就别装作有：服务层在取不到有效时段时一律返回 null 并回落保守策略，
--    绝不拿占位符当真价格参与决策或计费。

CREATE TABLE IF NOT EXISTS claw.elec_tou_slots (
    id             BIGSERIAL PRIMARY KEY,
    slot_code      VARCHAR(32) NOT NULL,        -- PEAK / FLAT / VALLEY
    season_tag     VARCHAR(16) NOT NULL DEFAULT 'ALL',
    start_minute   SMALLINT NOT NULL,           -- 0–1439
    end_minute     SMALLINT NOT NULL,           -- 0–1439（允许 < start_minute 表示跨零点）
    energy_price   NUMERIC(18,8) NOT NULL,      -- $/kWh
    demand_price   NUMERIC(18,8),               -- $/kW
    effective_from DATE,                        -- NULL = 一直有效
    priority       SMALLINT NOT NULL DEFAULT 0, -- 重叠时取小者
    enabled        BOOLEAN NOT NULL DEFAULT true,
    tenant_id      BIGINT NOT NULL DEFAULT 1,
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_elec_tou_slots_enabled_start ON claw.elec_tou_slots (enabled, start_minute);

-- 默认时段（柬埔寨场景占位电价：谷 00:00–07:00 / 平 07:00–18:00 / 峰 18:00–23:00 / 平 23:00–24:00）
INSERT INTO claw.elec_tou_slots (slot_code, season_tag, start_minute, end_minute, energy_price, demand_price, priority)
SELECT 'VALLEY', 'ALL', 0, 420, 0.08000000, 5.00000000, 0
WHERE NOT EXISTS (SELECT 1 FROM claw.elec_tou_slots WHERE slot_code = 'VALLEY' AND start_minute = 0 AND end_minute = 420);

INSERT INTO claw.elec_tou_slots (slot_code, season_tag, start_minute, end_minute, energy_price, demand_price, priority)
SELECT 'FLAT', 'ALL', 420, 1080, 0.15000000, 5.00000000, 0
WHERE NOT EXISTS (SELECT 1 FROM claw.elec_tou_slots WHERE slot_code = 'FLAT' AND start_minute = 420 AND end_minute = 1080);

INSERT INTO claw.elec_tou_slots (slot_code, season_tag, start_minute, end_minute, energy_price, demand_price, priority)
SELECT 'PEAK', 'ALL', 1080, 1380, 0.22000000, 5.00000000, 0
WHERE NOT EXISTS (SELECT 1 FROM claw.elec_tou_slots WHERE slot_code = 'PEAK' AND start_minute = 1080 AND end_minute = 1380);

INSERT INTO claw.elec_tou_slots (slot_code, season_tag, start_minute, end_minute, energy_price, demand_price, priority)
SELECT 'FLAT', 'ALL', 1380, 1440, 0.15000000, 5.00000000, 0
WHERE NOT EXISTS (SELECT 1 FROM claw.elec_tou_slots WHERE slot_code = 'FLAT' AND start_minute = 1380 AND end_minute = 1440);

-- VPP 配置项（含 VPP_EXPORT_ALLOWED 的保守说明；已存在则不动，避免覆盖运营改动）
INSERT INTO claw.system_config (config_key, config_value, category, description, data_type, editable) VALUES
    ('VPP_SHADOW_MODE', 'true', 'VPP',
     '虚拟电厂影子模式：true=只生成建议并落 shadow=true 指令，绝不调用下发通道（默认开，安全底线）',
     'BOOLEAN', true),
    ('VPP_EXPORT_ALLOWED', 'false', 'VPP',
     '光伏余电是否允许上网。仅当确认存在净计量/余电回购时才可开启，否则应保守弃光——'
     '不存在收益模型时假设收益会导致错误的调度决策。TOU 电价接入后由价格信号驱动，本开关作为兜底保留。',
     'BOOLEAN', true),
    ('VPP_DEMAND_TARGET_W', '', 'VPP',
     '并网点需量目标 W（需量管理削峰阈值）。留空表示未配置，需量管理不生效。',
     'NUMBER', true),
    ('VPP_TIMEZONE', 'Asia/Phnom_Penh', 'VPP',
     'TOU 时段判定时区（Asia/Phnom_Penh = UTC+7）',
     'STRING', true)
ON CONFLICT (config_key) DO NOTHING;
