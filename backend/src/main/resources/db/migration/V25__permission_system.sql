-- V25：RBAC 权限目录（菜单/按钮）+ 角色权限矩阵 + 角色数据范围。
SET search_path = claw;

CREATE TABLE IF NOT EXISTS permissions (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code          VARCHAR(80) NOT NULL UNIQUE,   -- 如 menu:dashboard / btn:asset:export
  name          VARCHAR(120) NOT NULL,
  ptype         VARCHAR(16) NOT NULL DEFAULT 'MENU',  -- MENU / BUTTON
  parent_code   VARCHAR(80),
  path          VARCHAR(160),                  -- 前端路由
  sort_no       INT DEFAULT 0,
  icon          VARCHAR(40),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS role_permissions (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  role_id       BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
  permission_code VARCHAR(80) NOT NULL REFERENCES permissions(code) ON DELETE CASCADE,
  can_read      BOOLEAN NOT NULL DEFAULT FALSE,
  can_create    BOOLEAN NOT NULL DEFAULT FALSE,
  can_update    BOOLEAN NOT NULL DEFAULT FALSE,
  can_delete    BOOLEAN NOT NULL DEFAULT FALSE,
  can_export    BOOLEAN NOT NULL DEFAULT FALSE,
  buttons_json  TEXT DEFAULT '{}',             -- 按钮级有效/无效 {"export":true,"audit":false}
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (role_id, permission_code)
);

ALTER TABLE roles ADD COLUMN IF NOT EXISTS data_scope VARCHAR(16) NOT NULL DEFAULT 'SELF';  -- SELF/DEPARTMENT/ALL/TYPE
ALTER TABLE roles ADD COLUMN IF NOT EXISTS data_scope_types TEXT DEFAULT '[]';               -- 特殊授权可看的类型

-- 种子：后台菜单目录（覆盖现有模块 + 新增模块）
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no, icon) VALUES
  ('menu:dashboard','数据大屏','MENU',NULL,'/dashboard',10,'dashboard'),
  ('menu:operations','运营管理','MENU',NULL,NULL,20,'operations'),
  ('menu:orders','订单管理','MENU','menu:operations','/orders',21,NULL),
  ('menu:swap-orders','换电订单','MENU','menu:operations','/swap-orders',22,NULL),
  ('menu:stations','站点管理','MENU','menu:operations','/stations',23,NULL),
  ('menu:assets','资产管理','MENU','menu:operations','/assets',24,NULL),
  ('menu:asset-trace','资产溯源','MENU','menu:operations','/asset-trace',25,NULL),
  ('menu:manufacturer','厂家与商品','MENU',NULL,'/manufacturer',30,'factory'),
  ('menu:finance','财务管理','MENU',NULL,NULL,40,'finance'),
  ('menu:ledger','账本','MENU','menu:finance','/ledger',41,NULL),
  ('menu:settlements','结算','MENU','menu:finance','/settlements',42,NULL),
  ('menu:payments','支付','MENU','menu:finance','/payments',43,NULL),
  ('menu:deposits','押金','MENU','menu:finance','/deposits',44,NULL),
  ('menu:reconciliations','对账','MENU','menu:finance','/reconciliations',45,NULL),
  ('menu:profit','分账报告','MENU','menu:finance','/profit',46,NULL),
  ('menu:fee','费率配置','MENU','menu:finance','/fee',47,NULL),
  ('menu:risk','风控合规','MENU',NULL,NULL,50,'risk'),
  ('menu:risk-monitor','风控监控','MENU','menu:risk','/risk',51,NULL),
  ('menu:alerts','异常告警','MENU','menu:risk','/alerts',52,NULL),
  ('menu:custody','产权链','MENU','menu:risk','/custody',53,NULL),
  ('menu:arbitration','争议仲裁','MENU','menu:risk','/arbitration',54,NULL),
  ('menu:users','平台用户','MENU',NULL,'/users',60,NULL),
  ('menu:complaints','投诉','MENU',NULL,'/complaints',61,NULL),
  ('menu:countries','国家地区','MENU',NULL,'/countries',62,NULL),
  ('menu:system','系统设置','MENU',NULL,NULL,70,'setting'),
  ('menu:roles','角色权限','MENU','menu:system','/roles',71,NULL),
  ('menu:permission','权限矩阵','MENU','menu:system','/permission',72,NULL),
  ('menu:settings','系统配置','MENU','menu:system','/settings',73,NULL)
ON CONFLICT (code) DO NOTHING;

-- 示例按钮权限点
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('btn:asset:export','资产导出','BUTTON','menu:assets',1),
  ('btn:order:refund','订单退款','BUTTON','menu:orders',1),
  ('btn:user:freeze','用户冻结','BUTTON','menu:users',1)
ON CONFLICT (code) DO NOTHING;

-- 种子：平台固定角色（auto_grant=false）默认授予全部菜单的 增删改查+导出 权限，开箱可用
INSERT INTO role_permissions (role_id, permission_code, can_read, can_create, can_update, can_delete, can_export, buttons_json)
SELECT r.id, p.code, TRUE, TRUE, TRUE, TRUE, TRUE, '{"export":true,"audit":true,"refund":true,"freeze":true}'
FROM roles r, permissions p
WHERE r.auto_grant = FALSE
ON CONFLICT (role_id, permission_code) DO NOTHING;
