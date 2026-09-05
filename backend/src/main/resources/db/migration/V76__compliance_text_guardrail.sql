-- =====================================================================
-- Claw 平台 V76 增量（合规文案护栏配置）
-- 依据：低成本合规闸门（建议加）—— 营销/承诺类文案禁止表述清单
-- 范围：
--   · system_config: COMPLIANCE_PROHIBITED_KEYWORDS（JSON 数组，运营可维护）
--   · system_config: COMPLIANCE_REQUIRED_DISCLAIMER（可选风险提示语，含收益/回佣表述须包含）
-- 通用规范继承 V1/V17：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

INSERT INTO claw.system_config (config_key, config_value, category, description, data_type, editable)
SELECT 'COMPLIANCE_PROHIBITED_KEYWORDS',
       '[{"pattern":"保本","severity":"REJECT","category":"ILLEGAL_FUNDRAISING"},{"pattern":"稳赚","severity":"REJECT","category":"ILLEGAL_FUNDRAISING"},{"pattern":"稳收益","severity":"REJECT","category":"ILLEGAL_FUNDRAISING"},{"pattern":"零风险","severity":"REJECT","category":"MISLEADING"},{"pattern":"无风险","severity":"REJECT","category":"MISLEADING"},{"pattern":"高额返利","severity":"REJECT","category":"ILLEGAL_FUNDRAISING"},{"pattern":" guaranteed return","severity":"REJECT","category":"ILLEGAL_FUNDRAISING"},{"pattern":"guaranteed profit","severity":"REJECT","category":"ILLEGAL_FUNDRAISING"},{"pattern":"risk-free","severity":"REJECT","category":"MISLEADING"},{"pattern":"high return","severity":"WARN","category":"MISLEADING"},{"pattern":"ធានាចំណេញ","severity":"REJECT","category":"ILLEGAL_FUNDRAISING"}]',
       'COMPLIANCE', '合规文案护栏：禁止表述清单（JSON 数组，pattern/severity[REJECT|WARN]/category），运营可维护', 'JSON', true
WHERE NOT EXISTS (SELECT 1 FROM claw.system_config WHERE config_key = 'COMPLIANCE_PROHIBITED_KEYWORDS' AND deleted = false);

INSERT INTO claw.system_config (config_key, config_value, category, description, data_type, editable)
SELECT 'COMPLIANCE_REQUIRED_DISCLAIMER',
       '投资风险自担',
       'COMPLIANCE', '合规文案护栏：含收益/回佣表述的文案必须包含该风险提示语（命中 WARN）', 'STRING', true
WHERE NOT EXISTS (SELECT 1 FROM claw.system_config WHERE config_key = 'COMPLIANCE_REQUIRED_DISCLAIMER' AND deleted = false);
