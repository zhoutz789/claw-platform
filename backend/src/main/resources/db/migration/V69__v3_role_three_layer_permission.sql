-- =====================================================================
-- Claw 平台 V69 增量（权限三层「通电」· v3 新架构角色落到三层权限）
-- 依据：《终版需求文档》第 11 章 v3 新架构方向 + 11.6 通电方案
-- 周老板口径：权限三层（角色 / 功能权限 / 数据范围）为最高优先级启动项。
-- 范围：
--   1) 补种可能缺失的菜单码（对齐 web/src/nav.js 较新叶子：task-*/product-publish/merchants 等），
--      保证下方 role_template_permissions 挂载不触发外键缺失（幂等）。
--   2) role_templates：新增 DRIVER / PILOT / MERCHANT / REGULATOR
--      （MANUFACTURER / STATION 已在 V47 建，CUSTOMER / PLATFORM_ADMIN 已在 V47 建）。
--   3) roles：新增/复活 DRIVER / PILOT / MERCHANT（V11 曾置 INACTIVE，此处复活为独立"商家"，
--      与 MANUFACTURER 分离）/ REGULATOR。
--   4) role_template_permissions：为各 v3 角色挂载「菜单码 + 功能权限位」（资质驱动 + 数据范围）。
--   5) roles.grants 回写（沿用 V68 三步法：to_jsonb(array_agg)::text）。
--   6) data_scope：司机/飞手/商家=SELF（仅看本人数据）；厂家/服务站=CUSTOM（沿用 V47 空规则=仅本人）；
--      监管者=ALL（只读监管看板，方案 C 内嵌视图+上报网关预留）。
-- 全量幂等：INSERT ... ON CONFLICT DO NOTHING / DO UPDATE；UPDATE 仅覆盖 v3 角色，
--   不动 SUPER_ADMIN 通配与既有精细配置；CUSTOMER 的对象结构 grants 不在本脚本范围内。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 对齐 nav.js 的菜单码（仅补种缺失叶子，幂等） ----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:product-publish', '商品发布', 'MENU', 'menu:goods', '/product-publish', 46),
  ('menu:merchants',       '商家管理', 'MENU', 'menu:goods', '/merchants', 47),
  ('menu:task-logi',       '物流任务', 'MENU', 'menu:task', '/task-logi', 53),
  ('menu:task-ad',         '广告任务', 'MENU', 'menu:task', '/task-ad', 54),
  ('menu:task-video',      '视频任务', 'MENU', 'menu:task', '/task-video', 55),
  ('menu:task-near',       '附近任务', 'MENU', 'menu:task', '/task-near', 56),
  ('menu:airspace-zones',  '空域分区', 'MENU', 'menu:drone', '/airspace-zones', 91),
  ('menu:flight-plans',    '飞行计划', 'MENU', 'menu:drone', '/flight-plans', 92),
  ('menu:pilot-licenses',   '飞手资质', 'MENU', 'menu:drone', '/pilot-licenses', 93),
  ('menu:drone-ops',       '无人机作业', 'MENU', 'menu:drone', '/drone-ops', 94)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) role_templates：新增 v3 业务角色模板 ----------
INSERT INTO role_templates (code, name, principal_type, description) VALUES
  ('DRIVER',    '司机',   'DRIVER',    '司机角色：操作车辆相关权限，需线下培训+资格证书+官方资质认证'),
  ('PILOT',     '飞手',   'PILOT',     '飞手角色：操作无人机作业板块，需线下培训+资格证书+官方资质认证'),
  ('MERCHANT',  '商家',   'MERCHANT',  '商家角色：普通商品售卖（与厂家 MANUFACTURER 分离）'),
  ('REGULATOR', '监管者', 'REGULATOR', '无人机/低空监管者：只读监管看板（方案 C 内嵌视图+上报网关预留）')
ON CONFLICT (code) DO NOTHING;

-- ---------- 3) roles：新增 / 复活 v3 角色 ----------
-- MERCHANT 复活（V11 曾 INACTIVE）：复活为独立"商家"角色，与 MANUFACTURER 分家。
INSERT INTO roles (code, name_i18n, grants, auto_grant, status, data_scope, data_scope_types, data_rule_ids)
VALUES ('MERCHANT', 'role.merchant.name', '{}', FALSE, 'ACTIVE', 'SELF', '[]', '')
ON CONFLICT (code) DO UPDATE SET status = 'ACTIVE', data_scope = 'SELF', updated_at = now();

INSERT INTO roles (code, name_i18n, grants, auto_grant, status, data_scope, data_scope_types, data_rule_ids)
VALUES
  ('DRIVER',    'role.driver.name',    '{}', FALSE, 'ACTIVE', 'SELF', '[]', ''),
  ('PILOT',     'role.pilot.name',     '{}', FALSE, 'ACTIVE', 'SELF', '[]', ''),
  ('REGULATOR', 'role.regulator.name', '{}', FALSE, 'ACTIVE', 'ALL', '[]', '')
ON CONFLICT (code) DO UPDATE SET status = 'ACTIVE', updated_at = now();

-- ---------- 4) role_template_permissions：挂载菜单码 + 功能权限位 ----------
-- 司机 DRIVER：车辆操作 + 物流/租赁任务 + 入驻申请（仅本人数据 SELF）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('DRIVER', 'menu:workbench'),
  ('DRIVER', 'menu:assets'),
  ('DRIVER', 'menu:asset-trace'),
  ('DRIVER', 'menu:task-logi'),
  ('DRIVER', 'menu:task-rent'),
  ('DRIVER', 'menu:onboarding-apply'),
  ('DRIVER', 'asset:create'),
  ('DRIVER', 'asset:update'),
  ('DRIVER', 'asset:delete'),
  ('DRIVER', 'asset:export'),
  ('DRIVER', 'order:view'),
  ('DRIVER', 'order:fulfill:pay')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 飞手 PILOT：无人机作业板块 + 无人机任务 + 入驻申请（仅本人数据 SELF）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('PILOT', 'menu:workbench'),
  ('PILOT', 'menu:airspace-zones'),
  ('PILOT', 'menu:flight-plans'),
  ('PILOT', 'menu:pilot-licenses'),
  ('PILOT', 'menu:drone-ops'),
  ('PILOT', 'menu:task-drone'),
  ('PILOT', 'menu:onboarding-apply'),
  ('PILOT', 'asset:create'),
  ('PILOT', 'asset:update'),
  ('PILOT', 'asset:delete'),
  ('PILOT', 'asset:export'),
  ('PILOT', 'order:view')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 商家 MERCHANT：普通商品售卖 + 广告任务 + 入驻申请（仅本人数据 SELF）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MERCHANT', 'menu:workbench'),
  ('MERCHANT', 'menu:goods-list'),
  ('MERCHANT', 'menu:product-wizard'),
  ('MERCHANT', 'menu:product-publish'),
  ('MERCHANT', 'menu:order-manage'),
  ('MERCHANT', 'menu:merchants'),
  ('MERCHANT', 'menu:task-ad'),
  ('MERCHANT', 'menu:onboarding-apply'),
  ('MERCHANT', 'product:create'),
  ('MERCHANT', 'product:update'),
  ('MERCHANT', 'product:delete'),
  ('MERCHANT', 'product:export'),
  ('MERCHANT', 'order:create'),
  ('MERCHANT', 'order:update'),
  ('MERCHANT', 'order:delete'),
  ('MERCHANT', 'order:export'),
  ('MERCHANT', 'order:view'),
  ('MERCHANT', 'order:fulfill:pay')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 厂家 MANUFACTURER：发布运营资产（车辆/电池/充电桩/光伏/无人机）+ 供应流通（沿用 V47/V68 已挂载的 mfg:*/station:*）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER', 'menu:workbench'),
  ('MANUFACTURER', 'menu:product-center'),
  ('MANUFACTURER', 'menu:certificate'),
  ('MANUFACTURER', 'menu:bind-ownership'),
  ('MANUFACTURER', 'menu:product-template'),
  ('MANUFACTURER', 'menu:data-binding'),
  ('MANUFACTURER', 'menu:authorization'),
  ('MANUFACTURER', 'menu:manufacturer'),
  ('MANUFACTURER', 'menu:production'),
  ('MANUFACTURER', 'menu:mfg-inventory'),
  ('MANUFACTURER', 'menu:station-consignment'),
  ('MANUFACTURER', 'menu:transfers'),
  ('MANUFACTURER', 'menu:fulfillment-orders'),
  ('MANUFACTURER', 'menu:drone-ops'),
  ('MANUFACTURER', 'menu:onboarding-apply'),
  ('MANUFACTURER', 'product:create'),
  ('MANUFACTURER', 'product:update'),
  ('MANUFACTURER', 'product:delete'),
  ('MANUFACTURER', 'product:export'),
  ('MANUFACTURER', 'asset:create'),
  ('MANUFACTURER', 'asset:update'),
  ('MANUFACTURER', 'asset:delete'),
  ('MANUFACTURER', 'asset:export')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 服务站 STATION：仅补挂载工作台 + 入驻申请（既有 mfg:*/station:* 由 V47/V68 提供）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('STATION', 'menu:workbench'),
  ('STATION', 'menu:onboarding-apply')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 监管者 REGULATOR：只读监管看板（仅 :view 类权限位，无 create/update/delete/export）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('REGULATOR', 'menu:workbench'),
  ('REGULATOR', 'menu:airspace-zones'),
  ('REGULATOR', 'menu:flight-plans'),
  ('REGULATOR', 'menu:pilot-licenses'),
  ('REGULATOR', 'menu:drone-ops'),
  ('REGULATOR', 'menu:risk'),
  ('REGULATOR', 'menu:alerts'),
  ('REGULATOR', 'menu:insurance'),
  ('REGULATOR', 'menu:arbitration'),
  ('REGULATOR', 'menu:complaints'),
  ('REGULATOR', 'menu:assets'),
  ('REGULATOR', 'menu:asset-trace'),
  ('REGULATOR', 'menu:custody'),
  ('REGULATOR', 'menu:shared-pool'),
  ('REGULATOR', 'menu:recovery'),
  ('REGULATOR', 'menu:task-drone'),
  ('REGULATOR', 'menu:task-logi'),
  ('REGULATOR', 'menu:task-rent'),
  ('REGULATOR', 'menu:task-ad'),
  ('REGULATOR', 'menu:task-video'),
  ('REGULATOR', 'menu:task-near'),
  ('REGULATOR', 'menu:onboarding-apply'),
  ('REGULATOR', 'order:view'),
  ('REGULATOR', 'mfg:production:view'),
  ('REGULATOR', 'mfg:inventory:view'),
  ('REGULATOR', 'mfg:certificate:view'),
  ('REGULATOR', 'station:consignment:view')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 5) roles.grants 回写（v3 角色，沿用 V68 三步法） ----------
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('DRIVER', 'PILOT', 'MERCHANT', 'REGULATOR', 'MANUFACTURER', 'STATION');

-- ---------- 6) data_scope 落地（第三层：仅看本人/本厂/本站/全局） ----------
UPDATE roles SET data_scope = 'SELF'   WHERE code IN ('DRIVER', 'PILOT', 'MERCHANT');
UPDATE roles SET data_scope = 'CUSTOM' WHERE code IN ('MANUFACTURER', 'STATION');  -- 沿用 V47 空规则=仅本人
UPDATE roles SET data_scope = 'ALL'    WHERE code = 'REGULATOR';                    -- 只读监管看板
