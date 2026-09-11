-- =====================================================================
-- Claw 平台 V108 增量（能源品类：充电桩 / 光伏 / 储能 产品管理切片）
--
-- 目标：让能源品类（充电桩、光伏组件、逆变器、储能）的商品能像普通商品一样发布，
--       且专业参数字段不用每个商品手工重填 —— 由「品类级字段模板」在商品创建时复制。
--
-- 范围：
--   1) 分类树种子：categories 能源设备(E) → 光伏发电(E01)/储能(E02)/充电桩(E03)/配电与计量(E04)
--   2) 新建表 category_field_templates（品类级 EAV 字段模板，与 product_template_fields 同构，
--      外键由 product_id 换成 category_id）
--   3) 模板字段种子：充电桩(E0301~E0304) 10 个 / 光伏组件(E0101) 8 个 / 逆变器(E0102) 7 个
--
-- 幂等性：分类与模板种子一律用 INSERT ... SELECT ... WHERE NOT EXISTS 按 code / field_key 判重，
--        表与索引用 IF NOT EXISTS；父 id 一律用子查询按 code 取，不硬编码 id；二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 分类树种子（能源设备大类 + 二级 + 三级）----------
-- parent_id 用子查询按 code 取；level：根=0，二级=1，三级=2。
INSERT INTO categories (name, parent_id, level, sort_no, code, tenant_id)
SELECT v.name,
       (SELECT p.id FROM categories p WHERE p.code = v.parent_code LIMIT 1),
       v.level,
       v.sort_no,
       v.code,
       1
FROM (VALUES
    -- 一级：能源设备根分类
    ('能源设备'::varchar, NULL::varchar, 0, 0, 'E'::varchar),
    -- 二级
    ('光伏发电',   'E', 1, 1, 'E01'),
    ('储能',       'E', 1, 2, 'E02'),
    ('充电桩',     'E', 1, 3, 'E03'),
    ('配电与计量', 'E', 1, 4, 'E04'),
    -- 三级：光伏发电
    ('光伏组件',     'E01', 2, 1, 'E0101'),
    ('逆变器',       'E01', 2, 2, 'E0102'),
    ('支架与结构',   'E01', 2, 3, 'E0103'),
    ('汇流箱',       'E01', 2, 4, 'E0104'),
    ('线缆与辅材',   'E01', 2, 5, 'E0105'),
    -- 三级：储能
    ('电芯与电池包',   'E02', 2, 1, 'E0201'),
    ('储能变流器PCS',  'E02', 2, 2, 'E0202'),
    ('户用储能',       'E02', 2, 3, 'E0203'),
    ('工商业储能',     'E02', 2, 4, 'E0204'),
    -- 三级：充电桩
    ('交流慢充', 'E03', 2, 1, 'E0301'),
    ('直流快充', 'E03', 2, 2, 'E0302'),
    ('超充',     'E03', 2, 3, 'E0303'),
    ('换电柜',   'E03', 2, 4, 'E0304'),
    -- 三级：配电与计量
    ('双向电表', 'E04', 2, 1, 'E0401'),
    ('气象站',   'E04', 2, 2, 'E0402'),
    ('数采网关', 'E04', 2, 3, 'E0403')
) AS v(name, parent_code, level, sort_no, code)
WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.code = v.code);

-- ---------- 2) 品类级字段模板表 ----------
-- 与 claw.product_template_fields 同构：字段 schema 定义由「每商品一份」升级为「每品类一份」，
-- 商品创建时复制一份到 product_template_fields，实例值仍存 products.attr_json / params_json。
CREATE TABLE IF NOT EXISTS category_field_templates (
    id           BIGSERIAL PRIMARY KEY,
    category_id  BIGINT NOT NULL,
    field_key    VARCHAR(64) NOT NULL,
    label        VARCHAR(120) NOT NULL,
    type         VARCHAR(16) NOT NULL,          -- number/text/select/date/boolean
    unit         VARCHAR(16),
    options_json TEXT,                          -- select 选项（JSON 数组）
    required     BOOLEAN NOT NULL DEFAULT false,
    sort_no      INT DEFAULT 0,
    tenant_id    BIGINT NOT NULL DEFAULT 1,
    -- TIMESTAMPTZ：与 product_template_fields.created_at 保持一致，
    -- 实体 createdAt 为 java.time.Instant，Hibernate 默认按 TIMESTAMP WITH TIME ZONE 读写。
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (category_id, field_key)
);
CREATE INDEX IF NOT EXISTS idx_category_field_templates_category ON category_field_templates (category_id);

-- ---------- 3.1) 充电桩字段模板（E0301 交流慢充 / E0302 直流快充 / E0303 超充 / E0304 换电柜）----------
INSERT INTO category_field_templates
    (category_id, field_key, label, type, unit, options_json, required, sort_no, tenant_id)
SELECT (SELECT c.id FROM categories c WHERE c.code = cat.code LIMIT 1),
       f.field_key,
       f.label,
       f.type,
       f.unit,
       f.options_json,
       f.required,
       f.sort_no,
       1
FROM (VALUES ('E0301'::varchar), ('E0302'), ('E0303'), ('E0304')) AS cat(code)
CROSS JOIN (VALUES
    ('rated_power_kw'::varchar,       '额定功率'::varchar,     'number'::varchar,  'kW'::varchar, NULL::text, true,  1),
    ('gun_count',                     '充电枪数',              'number',           '个',          NULL,       true,  2),
    ('gun_type',                      '枪型',                  'select',           NULL,          '["GB/T","CCS1","CCS2","CHAdeMO","Type2"]', true, 3),
    ('output_voltage_range',          '输出电压范围',          'text',             'V',           NULL,       true,  4),
    ('max_current_a',                 '最大输出电流',          'number',           'A',           NULL,       true,  5),
    ('ip_rating',                     '防护等级',              'select',           NULL,          '["IP54","IP55","IP65"]', true, 6),
    ('comm_protocol',                 '通信协议',              'select',           NULL,          '["OCPP1.6J","OCPP2.0.1","厂家私有"]', true, 7),
    ('billing_mode',                  '计费模式',              'select',           NULL,          '["按电量","按时长","阶梯"]', false, 8),
    ('v2g_ready',                     '支持V2G',               'boolean',          NULL,          NULL,       false, 9),
    ('certifications',                '认证',                  'select',           NULL,          '["CE","TUV","UL"]', false, 10)
) AS f(field_key, label, type, unit, options_json, required, sort_no)
WHERE NOT EXISTS (
    SELECT 1 FROM category_field_templates t
     WHERE t.field_key = f.field_key
       AND t.category_id = (SELECT c2.id FROM categories c2 WHERE c2.code = cat.code LIMIT 1)
);

-- ---------- 3.2) 光伏组件字段模板（E0101）----------
INSERT INTO category_field_templates
    (category_id, field_key, label, type, unit, options_json, required, sort_no, tenant_id)
SELECT (SELECT c.id FROM categories c WHERE c.code = cat.code LIMIT 1),
       f.field_key,
       f.label,
       f.type,
       f.unit,
       f.options_json,
       f.required,
       f.sort_no,
       1
FROM (VALUES ('E0101'::varchar)) AS cat(code)
CROSS JOIN (VALUES
    ('pmax_w'::varchar,                  '峰值功率'::varchar,      'number'::varchar, 'Wp'::varchar, NULL::text, true,  1),
    ('efficiency',                       '转换效率',                'number',          '%',           NULL,       false, 2),
    ('cell_type',                        '电池片类型',              'select',          NULL,          '["PERC","TOPCon","HJT","单晶","多晶"]', false, 3),
    ('size_mm',                          '组件尺寸',                'text',            'mm',          NULL,       false, 4),
    ('temp_coefficient',                 '功率温度系数',            'number',          '%/℃',         NULL,       false, 5),
    ('first_year_degradation',           '首年衰减',                'number',          '%',           NULL,       false, 6),
    ('linear_degradation',               '线性年衰减',              'number',          '%',           NULL,       false, 7),
    ('warranty_years',                   '质保年限',                'number',          '年',          NULL,       false, 8)
) AS f(field_key, label, type, unit, options_json, required, sort_no)
WHERE NOT EXISTS (
    SELECT 1 FROM category_field_templates t
     WHERE t.field_key = f.field_key
       AND t.category_id = (SELECT c2.id FROM categories c2 WHERE c2.code = cat.code LIMIT 1)
);

-- ---------- 3.3) 逆变器字段模板（E0102）----------
INSERT INTO category_field_templates
    (category_id, field_key, label, type, unit, options_json, required, sort_no, tenant_id)
SELECT (SELECT c.id FROM categories c WHERE c.code = cat.code LIMIT 1),
       f.field_key,
       f.label,
       f.type,
       f.unit,
       f.options_json,
       f.required,
       f.sort_no,
       1
FROM (VALUES ('E0102'::varchar)) AS cat(code)
CROSS JOIN (VALUES
    ('rated_power_kw'::varchar,     '额定功率'::varchar,      'number'::varchar, 'kW'::varchar, NULL::text, true,  1),
    ('mppt_count',                  'MPPT路数',               'number',          NULL,          NULL,       false, 2),
    ('max_dc_voltage_v',            '最大输入电压',           'number',          'V',           NULL,       false, 3),
    ('mppt_voltage_range',          'MPPT电压范围',           'text',            'V',           NULL,       false, 4),
    ('euro_efficiency',             '欧洲效率',               'number',          '%',           NULL,       false, 5),
    ('ip_rating',                   '防护等级',               'select',          NULL,          '["IP54","IP55","IP65"]', false, 6),
    ('comm_type',                   '通信方式',               'select',          NULL,          '["WiFi","4G","RS485","PLC"]', false, 7)
) AS f(field_key, label, type, unit, options_json, required, sort_no)
WHERE NOT EXISTS (
    SELECT 1 FROM category_field_templates t
     WHERE t.field_key = f.field_key
       AND t.category_id = (SELECT c2.id FROM categories c2 WHERE c2.code = cat.code LIMIT 1)
);
