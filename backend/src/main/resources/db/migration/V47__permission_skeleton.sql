-- =====================================================================
-- Claw 平台 V47 增量（权限骨架 · 增量 A）
-- 依据：增量PRD/设计 §2.1（权限骨架）。
-- 范围：
--   · roles：新增 MANUFACTURER / STATION 两个业务角色码（与厂家/服务站主体绑定）
--   · role_templates（4 类角色模板：MANUFACTURER/STATION/CUSTOMER/PLATFORM_ADMIN）
--   · role_template_permissions（模板 ↔ 权限码，引用既有 permissions 原子码子集）
--   · role_groups（角色组，系统管理第 6 项能力载体）
--   · role_group_templates（组 ↔ 模板）
--   · principal_bindings（账号 ↔ 业务主体 1:1，Q5 严格 1:1）
--   · permissions：新增增量 B 所需的 ~24 个权限码（按钮级）
-- 全量幂等：CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS / ON CONFLICT DO NOTHING。
-- 说明：既有 6+ 角色码（CONSUMER/PRODUCER/...）保留并存；本增量仅新增 MANUFACTURER/STATION
--       并以其 code 作为 role_templates 的 principal 视角键。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 新增业务角色码 MANUFACTURER / STATION ----------
INSERT INTO roles (code, name_i18n, grants, auto_grant, status, data_scope, data_scope_types, data_rule_ids)
VALUES
    ('MANUFACTURER', 'role.manufacturer.name', '{}', FALSE, 'ACTIVE', 'CUSTOM', '[]', ''),
    ('STATION',      'role.station.name',      '{}', FALSE, 'ACTIVE', 'CUSTOM', '[]', '')
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 权限码（增量 B 所需，按钮级）----------
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('mfg:production:create',          '厂家生产创建',     'BUTTON', 'menu:manufacturer', 1),
  ('mfg:production:view',            '厂家生产查看',     'BUTTON', 'menu:manufacturer', 2),
  ('mfg:inventory:view',             '厂家库存查看',     'BUTTON', 'menu:manufacturer', 3),
  ('mfg:inventory:own',              '厂家自有库存',     'BUTTON', 'menu:manufacturer', 4),
  ('mfg:inventory:consignment:view', '厂家寄售库存查看', 'BUTTON', 'menu:manufacturer', 5),
  ('mfg:certificate:view',           '合格证查看',       'BUTTON', 'menu:manufacturer', 6),
  ('mfg:certificate:print',          '合格证补打',       'BUTTON', 'menu:manufacturer', 7),
  ('mfg:transfer:create',            '调拨单创建',       'BUTTON', 'menu:manufacturer', 8),
  ('mfg:fulfill:ship',               '厂家发货',         'BUTTON', 'menu:manufacturer', 9),
  ('mfg:recovery:create',            '回收单创建',       'BUTTON', 'menu:manufacturer', 10),
  ('station:consignment:view',       '服务站寄售查看',   'BUTTON', 'menu:stations',     1),
  ('station:consignment:receive',    '服务站收货',       'BUTTON', 'menu:stations',     2),
  ('station:transfer:handover',      '调拨扫码交接',     'BUTTON', 'menu:stations',     3),
  ('station:transfer:receive',       '调拨扫码收货',     'BUTTON', 'menu:stations',     4),
  ('order:fulfill:confirm',          '服务站确认选品',   'BUTTON', 'menu:orders',       1),
  ('station:fulfill:receive',        '服务站订单收货',   'BUTTON', 'menu:orders',       2),
  ('order:pickup:scan',              '取货扫码',         'BUTTON', 'menu:orders',       3),
  ('station:pickup:confirm',         '服务站取货确认',   'BUTTON', 'menu:orders',       4),
  ('station:recovery:confirm',       '服务站回收确认',   'BUTTON', 'menu:stations',     5),
  ('config:commission:manage',       '提成规则配置',     'BUTTON', 'menu:settings',     1),
  ('order:fulfill:pay',              '用户付款',         'BUTTON', 'menu:orders',       5),
  ('order:view',                     '订单查看',         'BUTTON', 'menu:orders',       6),
  ('role:template:manage',           '角色模板管理',     'BUTTON', 'menu:roles',        1),
  ('role:group:manage',              '角色组管理',       'BUTTON', 'menu:roles',        2),
  ('mfg:bind:manage',                '厂家绑定管理',     'BUTTON', 'menu:roles',        3),
  ('station:bind:manage',            '服务站绑定管理',   'BUTTON', 'menu:roles',        4)
ON CONFLICT (code) DO NOTHING;

-- ---------- 3) 角色模板表 ----------
CREATE TABLE IF NOT EXISTS role_templates (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(40) NOT NULL UNIQUE,   -- MANUFACTURER / STATION / CUSTOMER / PLATFORM_ADMIN
    name            VARCHAR(80) NOT NULL,
    principal_type  VARCHAR(20) NOT NULL,          -- 与 code 同义，便于 @DataScope 解析
    description     TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO role_templates (code, name, principal_type, description) VALUES
    ('MANUFACTURER',   '厂家',     'MANUFACTURER', '厂家业务角色模板（生产/库存/合格证/调拨/发货/回收）'),
    ('STATION',        '服务站',   'STATION',      '服务站业务角色模板（寄售/调拨交接/确认选品/取货/回收确认）'),
    ('CUSTOMER',       '用户',     'CUSTOMER',     '终端用户业务角色模板（付款/查看/取货）'),
    ('PLATFORM_ADMIN', '平台管理员','PLATFORM_ADMIN','平台管理员业务角色模板（全部 + 配置/角色治理）')
ON CONFLICT (code) DO NOTHING;

-- ---------- 4) 模板 ↔ 权限码 ----------
CREATE TABLE IF NOT EXISTS role_template_permissions (
    id              BIGSERIAL PRIMARY KEY,
    template_code   VARCHAR(40) NOT NULL REFERENCES role_templates(code) ON DELETE CASCADE,
    permission_code VARCHAR(80) NOT NULL REFERENCES permissions(code) ON DELETE CASCADE,
    UNIQUE (template_code, permission_code)
);

-- 厂家模板
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
    ('MANUFACTURER', 'mfg:production:create'),
    ('MANUFACTURER', 'mfg:production:view'),
    ('MANUFACTURER', 'mfg:inventory:view'),
    ('MANUFACTURER', 'mfg:inventory:own'),
    ('MANUFACTURER', 'mfg:inventory:consignment:view'),
    ('MANUFACTURER', 'mfg:certificate:view'),
    ('MANUFACTURER', 'mfg:certificate:print'),
    ('MANUFACTURER', 'mfg:transfer:create'),
    ('MANUFACTURER', 'mfg:fulfill:ship'),
    ('MANUFACTURER', 'mfg:recovery:create'),
    ('MANUFACTURER', 'order:view')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 服务站模板
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
    ('STATION', 'station:consignment:view'),
    ('STATION', 'station:consignment:receive'),
    ('STATION', 'station:transfer:handover'),
    ('STATION', 'station:transfer:receive'),
    ('STATION', 'order:fulfill:confirm'),
    ('STATION', 'station:fulfill:receive'),
    ('STATION', 'order:pickup:scan'),
    ('STATION', 'station:pickup:confirm'),
    ('STATION', 'station:recovery:confirm'),
    ('STATION', 'order:view')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 用户模板
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
    ('CUSTOMER', 'order:fulfill:pay'),
    ('CUSTOMER', 'order:view'),
    ('CUSTOMER', 'order:pickup:scan')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 平台管理员模板（全部 + 治理）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
    ('PLATFORM_ADMIN', 'mfg:production:create'),
    ('PLATFORM_ADMIN', 'mfg:production:view'),
    ('PLATFORM_ADMIN', 'mfg:inventory:view'),
    ('PLATFORM_ADMIN', 'mfg:inventory:own'),
    ('PLATFORM_ADMIN', 'mfg:inventory:consignment:view'),
    ('PLATFORM_ADMIN', 'mfg:certificate:view'),
    ('PLATFORM_ADMIN', 'mfg:certificate:print'),
    ('PLATFORM_ADMIN', 'mfg:transfer:create'),
    ('PLATFORM_ADMIN', 'mfg:fulfill:ship'),
    ('PLATFORM_ADMIN', 'mfg:recovery:create'),
    ('PLATFORM_ADMIN', 'station:consignment:view'),
    ('PLATFORM_ADMIN', 'station:consignment:receive'),
    ('PLATFORM_ADMIN', 'station:transfer:handover'),
    ('PLATFORM_ADMIN', 'station:transfer:receive'),
    ('PLATFORM_ADMIN', 'order:fulfill:confirm'),
    ('PLATFORM_ADMIN', 'station:fulfill:receive'),
    ('PLATFORM_ADMIN', 'order:pickup:scan'),
    ('PLATFORM_ADMIN', 'station:pickup:confirm'),
    ('PLATFORM_ADMIN', 'station:recovery:confirm'),
    ('PLATFORM_ADMIN', 'config:commission:manage'),
    ('PLATFORM_ADMIN', 'order:fulfill:pay'),
    ('PLATFORM_ADMIN', 'order:view'),
    ('PLATFORM_ADMIN', 'role:template:manage'),
    ('PLATFORM_ADMIN', 'role:group:manage'),
    ('PLATFORM_ADMIN', 'mfg:bind:manage'),
    ('PLATFORM_ADMIN', 'station:bind:manage')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 5) 角色组 + 组↔模板 ----------
CREATE TABLE IF NOT EXISTS role_groups (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(40) NOT NULL UNIQUE,
    name        VARCHAR(80) NOT NULL,
    description TEXT,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS role_group_templates (
    id            BIGSERIAL PRIMARY KEY,
    group_code    VARCHAR(40) NOT NULL REFERENCES role_groups(code) ON DELETE CASCADE,
    template_code VARCHAR(40) NOT NULL REFERENCES role_templates(code) ON DELETE CASCADE,
    UNIQUE (group_code, template_code)
);

INSERT INTO role_groups (code, name, description) VALUES
    ('MFG_STATION_OPS', '厂家-服务站运营组', '厂家与服务站联合运营治理组')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_group_templates (group_code, template_code) VALUES
    ('MFG_STATION_OPS', 'MANUFACTURER'),
    ('MFG_STATION_OPS', 'STATION')
ON CONFLICT (group_code, template_code) DO NOTHING;

-- ---------- 6) 主体绑定（账号 ↔ 厂家/服务站，1:1，Q5）----------
CREATE TABLE IF NOT EXISTS principal_bindings (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT NOT NULL REFERENCES accounts(id),
    principal_type  VARCHAR(20) NOT NULL,   -- MANUFACTURER / STATION
    principal_id    BIGINT NOT NULL,         -- manufacturer_id 或 station_id
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, principal_type)
);
