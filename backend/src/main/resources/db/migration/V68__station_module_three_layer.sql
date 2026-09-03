-- =====================================================================
-- Claw 平台 V68 增量（模块四 · 服务站功能：库存 / 项目 / 结算三层解耦）
-- 依据：增量设计-模块四-服务站三层解耦.md
-- 范围：
--   1) station_inventory_movements    库存出入库流水（审计 + 消耗数据源）
--   2) station_projects               服务站下项目（自引用树，与 projects 解耦）
--   3) station_project_inventory_alloc 项目按 ID 占用库存（逻辑占用，不扣库存）
--   4) station_settlements            服务站寄售结算单
--   5) station_settlement_items       结算明细（按 item_type 拆分物流费/提成/厂家净额）
--   6) system_config 种子 STATION_LOGISTICS_FEE_RATE（表已存在 V17，仅补种子）
--   7) 权限种子（三步法，沿用 V67）
--       7.1 菜单码 menu:station*（分组 + 三页）
--       7.2 资源（BUTTON）权限码 station:* / mfg:station:*（端点的 @RequirePermission 位）
--       7.3 角色模板挂载（MANUFACTURER / STATION / PLATFORM_ADMIN）
--       7.4 roles.grants 回写（只覆盖 MANUFACTURER / STATION，避开 PLATFORM_ADMIN 通配与 CUSTOMER）
-- 全量幂等（IF NOT EXISTS / ON CONFLICT DO NOTHING）。不修改任何既有表结构。
--
-- 注：相比设计文档 §2.2/§7（仅列 menu:station-*），本迁移额外补种了端点所需的
--     BUTTON 资源权限码（station:inventory:inbound 等）。原因：@RequirePermission 校验的是
--     资源权限位，若不补种，则除 PLATFORM_ADMIN 通配外所有角色在写接口上都会 403，
--     模块四对 STATION/MANUFACTURER 不可用（违反 BC-5 可用性与权限矩阵 §5）。
--     补种遵循 V47 既有范式（BUTTON 码 + 模板挂载 + grants 回写），不改动任何既有权限行。
-- =====================================================================

SET search_path = claw;

-- 1) 库存出入库流水（库存层的"消耗/活动"数据源，供结算层只读）
CREATE TABLE IF NOT EXISTS station_inventory_movements (
    id               BIGSERIAL PRIMARY KEY,
    station_id       BIGINT       NOT NULL REFERENCES stations (id),
    sku_code         VARCHAR(64)  NOT NULL,
    delta_qty        INT          NOT NULL,                 -- + 入站/盘盈；- 消耗/盘亏/回收
    reason           VARCHAR(32)  NOT NULL,                 -- INBOUND / ADJUST / CONSUME / RECOVER
    station_project_id BIGINT,                              -- 可空：归因到某站下项目（结算按项目活动拆分）
    ref_type         VARCHAR(32),                           -- 可选业务来源类型
    ref_id           BIGINT,                                -- 可选业务来源 ID
    operator_id      BIGINT,                                -- 操作人
    tenant_id        BIGINT       NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sim_station_sku ON station_inventory_movements (station_id, sku_code);
CREATE INDEX IF NOT EXISTS idx_sim_created     ON station_inventory_movements (created_at);
CREATE INDEX IF NOT EXISTS idx_sim_project     ON station_inventory_movements (station_project_id);

-- 2) 服务站下项目（自引用树；与既有 projects 解耦）
CREATE TABLE IF NOT EXISTS station_projects (
    id           BIGSERIAL PRIMARY KEY,
    station_id   BIGINT       NOT NULL REFERENCES stations (id),
    owner_user_id BIGINT      NOT NULL,
    name         VARCHAR(120) NOT NULL,
    parent_id    BIGINT,                                      -- 自引用：父项目（根=NULL）
    depth        INT          NOT NULL DEFAULT 0,
    sort_no      INT          NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE / ARCHIVED
    tenant_id    BIGINT       NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sp_station     ON station_projects (station_id, status);
CREATE INDEX IF NOT EXISTS idx_sp_parent      ON station_projects (parent_id);

-- 3) 项目按 ID 占用库存（逻辑占用，不扣减 station_stock）
CREATE TABLE IF NOT EXISTS station_project_inventory_alloc (
    id                 BIGSERIAL PRIMARY KEY,
    station_project_id BIGINT NOT NULL REFERENCES station_projects (id),
    station_stock_id   BIGINT NOT NULL REFERENCES station_stock (id),  -- 仅 ID 引用库存层
    sku_code           VARCHAR(64) NOT NULL,
    allocated_qty      INT    NOT NULL DEFAULT 0,
    note               VARCHAR(255),
    tenant_id          BIGINT NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_sp_alloc UNIQUE (station_project_id, station_stock_id)
);
CREATE INDEX IF NOT EXISTS idx_spia_stock ON station_project_inventory_alloc (station_stock_id);

-- 4) 服务站寄售结算单
CREATE TABLE IF NOT EXISTS station_settlements (
    id               BIGSERIAL PRIMARY KEY,
    settlement_no    VARCHAR(40) NOT NULL UNIQUE,
    station_id       BIGINT      NOT NULL REFERENCES stations (id),
    period_start     TIMESTAMPTZ,
    period_end       TIMESTAMPTZ,
    status           VARCHAR(16) NOT NULL DEFAULT 'DRAFT',       -- DRAFT / CONFIRMED / PAID
    logistics_fee    NUMERIC(16,2) NOT NULL DEFAULT 0,           -- 物流费（厂家承担）
    station_commission NUMERIC(16,2) NOT NULL DEFAULT 0,        -- 服务站提成
    manufacturer_net NUMERIC(16,2) NOT NULL DEFAULT 0,          -- 厂家净额 = 货值 - 物流费 - 提成
    currency         VARCHAR(8)  NOT NULL DEFAULT 'USD',
    created_by       BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at     TIMESTAMPTZ,
    paid_at          TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_ss_station ON station_settlements (station_id, status);

-- 5) 结算明细
CREATE TABLE IF NOT EXISTS station_settlement_items (
    id           BIGSERIAL PRIMARY KEY,
    settlement_id BIGINT     NOT NULL REFERENCES station_settlements (id),
    item_type    VARCHAR(24) NOT NULL,                         -- LOGISTICS / COMMISSION / RECOVERY / ADJUST
    ref_id       BIGINT,                                       -- 仅 ID 引用：movements/alloc/recovery_order
    description  VARCHAR(255),
    amount       NUMERIC(16,2) NOT NULL,
    direction    VARCHAR(8)  NOT NULL,                         -- DEBIT(厂家出) / CREDIT(服务站入)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ssi_settlement ON station_settlement_items (settlement_id);

-- 6) system_config 种子（物流费率，默认 0，代表"厂家承担但不额外计费"，可由运营在 settings 页调整）
INSERT INTO system_config (config_key, config_value, category, description, data_type, editable) VALUES
    ('STATION_LOGISTICS_FEE_RATE', '0', '结算', '服务站寄售结算物流费率（货值比例），由厂家承担，默认 0', 'NUMBER', true)
ON CONFLICT (config_key) DO NOTHING;

-- =====================================================================
-- 7) 权限种子（三步法，沿用 V67 / V47 范式）
-- ⚠️ 陷阱 1：PLATFORM_ADMIN 已靠 V40 种下的 '["*"]' 通配符拥有全部权限位，
--    不在第 7.4 步回写范围内（否则会把超管从「通配」降级为「白名单」）。
-- ⚠️ 陷阱 2：CUSTOMER 的 grants 是 V11 种下的对象结构，parseGrants 只认数组，
--    动了会清空老用户权限 —— 第 7.4 步严格限定 MANUFACTURER / STATION，绝不碰 CUSTOMER。
-- ⚠️ 陷阱 3：roles.grants 在 V18 已从 JSONB 改为 TEXT，回写时必须 ::text。
-- ⚠️ 陷阱 4：permissions 表有 icon 列（可空），沿用 V62 的 6 列写法。
-- 幂等性：INSERT 带 ON CONFLICT DO NOTHING；UPDATE 按 code 限定；二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 7.1) 菜单码（服务站分组 + 三页）----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:station',            '服务站',     'MENU', NULL,                '/station',            460),
  ('menu:station-inventory',  '服务站库存', 'MENU', 'menu:station', '/station-inventory',  1),
  ('menu:station-projects',   '服务站项目', 'MENU', 'menu:station', '/station-projects',   2),
  ('menu:station-settlements','服务站结算', 'MENU', 'menu:station', '/station-settlements', 3)
ON CONFLICT (code) DO NOTHING;

-- ---------- 7.2) 资源（BUTTON）权限码：端点 @RequirePermission 位 ----------
-- 沿用 V47 的 (code, name, ptype, parent_code, sort_no) 5 列写法。
-- mfg:inventory:consignment:view 已在 V47 种下，此处 ON CONFLICT 跳过，不重复插。
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('station:inventory:view',        '服务站库存查看',     'BUTTON', 'menu:station', 10),
  ('station:inventory:inbound',     '服务站入站收货',     'BUTTON', 'menu:station', 11),
  ('station:inventory:adjust',      '服务站盘点调整',     'BUTTON', 'menu:station', 12),
  ('station:project:view',          '服务站项目查看',     'BUTTON', 'menu:station', 20),
  ('station:project:manage',        '服务站项目管理',     'BUTTON', 'menu:station', 21),
  ('station:project:alloc',         '服务站项目占用',     'BUTTON', 'menu:station', 22),
  ('station:settlement:view',       '服务站结算查看',     'BUTTON', 'menu:station', 30),
  ('station:settlement:manage',     '服务站结算管理',     'BUTTON', 'menu:station', 31),
  ('mfg:station:project:view',      '厂家查看服务站项目', 'BUTTON', 'menu:station', 41),
  ('mfg:station:settlement:view',    '厂家查看服务站结算', 'BUTTON', 'menu:station', 42)
ON CONFLICT (code) DO NOTHING;

-- ---------- 7.3) 角色模板挂载（厂家 / 服务站 / 平台管理员）----------
-- 菜单码
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER',    'menu:station'),
  ('MANUFACTURER',    'menu:station-inventory'),
  ('MANUFACTURER',    'menu:station-projects'),
  ('MANUFACTURER',    'menu:station-settlements'),
  ('STATION',         'menu:station'),
  ('STATION',         'menu:station-inventory'),
  ('STATION',         'menu:station-projects'),
  ('STATION',         'menu:station-settlements'),
  ('PLATFORM_ADMIN',  'menu:station'),
  ('PLATFORM_ADMIN',  'menu:station-inventory'),
  ('PLATFORM_ADMIN',  'menu:station-projects'),
  ('PLATFORM_ADMIN',  'menu:station-settlements')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 资源权限码（按权限矩阵 §5 分配）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  -- 厂家：库存/项目/结算 只读（mfg:inventory:consignment:view 已在 V47 挂载，不重复）
  ('MANUFACTURER',    'station:inventory:view'),
  ('MANUFACTURER',    'station:project:view'),
  ('MANUFACTURER',    'mfg:station:project:view'),
  ('MANUFACTURER',    'station:settlement:view'),
  ('MANUFACTURER',    'mfg:station:settlement:view'),
  -- 服务站：自身全量操作（结算仅查看 + 发起草稿，确认/支付由平台管理员）
  ('STATION',         'station:inventory:view'),
  ('STATION',         'station:inventory:inbound'),
  ('STATION',         'station:inventory:adjust'),
  ('STATION',         'station:project:view'),
  ('STATION',         'station:project:manage'),
  ('STATION',         'station:project:alloc'),
  ('STATION',         'station:settlement:view'),
  -- 平台管理员：通配，挂载仅作显式登记（不影响通配判定）
  ('PLATFORM_ADMIN',  'station:inventory:view'),
  ('PLATFORM_ADMIN',  'station:inventory:inbound'),
  ('PLATFORM_ADMIN',  'station:inventory:adjust'),
  ('PLATFORM_ADMIN',  'station:project:view'),
  ('PLATFORM_ADMIN',  'station:project:manage'),
  ('PLATFORM_ADMIN',  'station:project:alloc'),
  ('PLATFORM_ADMIN',  'station:settlement:view'),
  ('PLATFORM_ADMIN',  'station:settlement:manage')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 7.4) 回写 roles.grants（权限真源）—— 沿用 V54 / V62 模式 ----------
-- 只覆盖业务角色码；PLATFORM_ADMIN 走通配符（见陷阱 1）；
-- CUSTOMER 是对象结构，禁止纳入（见陷阱 2）。
-- 此时 7.1~7.3 已全部落库，故回写会把新增的 menu:station* 与 station:* 资源位一并下发给已绑定账号。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'STATION');
