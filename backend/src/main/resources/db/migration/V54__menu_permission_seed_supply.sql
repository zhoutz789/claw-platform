-- =====================================================================
-- Claw 平台 V54 增量（补齐 11 个新页面的 menu:* 权限码 + 分组行）
--
-- 背景（缺陷）：V47 只把增量 B 的按钮码挂在既有 menu:manufacturer / menu:stations /
-- menu:orders / menu:settings / menu:roles 之下，没有为新页面建 menu:* 行。
-- 而前端 permStore.Perm 判定菜单可见性用的是 hasPerm('menu:{navKey}')，
-- 「后端权威菜单」也按 menu:* 过滤 —— 结果 11 个新页面在真实后端下不显示。
--
-- 修复：按 web/src/nav.js 的真实 key 建 12 行 MENU 权限码（11 页 + 1 个分组），
--       并把这些菜单码挂进 4 个角色模板，最后把模板展开结果回写 roles.grants
--       （权限真源），使「绑定即授权」后菜单立即可见。
--
-- 字段对齐 V40/V43/V45：permissions(code, name, ptype, parent_code, path, sort_no)
-- 全量幂等：INSERT ... ON CONFLICT (code) DO NOTHING。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 分组行：供应流通（nav.js 的 supply 分组，位于 goods(40) 与 task(50) 之间）----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:supply', '供应流通', 'MENU', NULL, NULL, 45)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 供应流通分组下的 7 个页面 ----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:production',          '生产管理',       'MENU', 'menu:supply', '/production',         451),
  ('menu:mfg-inventory',       '厂家库存',       'MENU', 'menu:supply', '/mfg-inventory',      452),
  ('menu:station-consignment', '服务站寄售库存', 'MENU', 'menu:supply', '/station-consignment',453),
  ('menu:transfers',           '调拨单',         'MENU', 'menu:supply', '/transfers',          454),
  ('menu:fulfillment-orders',  '待履约订单',     'MENU', 'menu:supply', '/fulfillment-orders', 455),
  ('menu:pickup-scan',         '取货扫码',       'MENU', 'menu:supply', '/pickup-scan',        456),
  ('menu:commission-rules',    '提成规则',       'MENU', 'menu:supply', '/commission-rules',   457)
ON CONFLICT (code) DO NOTHING;

-- ---------- 3) 权限骨架 3 页（系统设置分组，位于 roles(93) 与 permission(94) 之间）----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:role-templates',     '角色模板', 'MENU', 'menu:sys', '/role-templates',     931),
  ('menu:role-groups',        '角色组',   'MENU', 'menu:sys', '/role-groups',        932),
  ('menu:principal-bindings', '主体绑定', 'MENU', 'menu:sys', '/principal-bindings', 933)
ON CONFLICT (code) DO NOTHING;

-- ---------- 4) Phase 2 骨架页：商家入驻（商品管理分组，紧跟 order-manage(45)）----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:merchants', '商家入驻', 'MENU', 'menu:goods', '/merchants', 46)
ON CONFLICT (code) DO NOTHING;

-- ---------- 5) 把菜单码挂进 4 个角色模板 ----------
-- 厂家：供应流通 + 生产/库存/寄售/调拨/履约/取货/提成
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER', 'menu:supply'),
  ('MANUFACTURER', 'menu:production'),
  ('MANUFACTURER', 'menu:mfg-inventory'),
  ('MANUFACTURER', 'menu:station-consignment'),
  ('MANUFACTURER', 'menu:transfers'),
  ('MANUFACTURER', 'menu:fulfillment-orders'),
  ('MANUFACTURER', 'menu:pickup-scan'),
  ('MANUFACTURER', 'menu:commission-rules')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 服务站：供应流通 + 寄售/调拨/履约/取货
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('STATION', 'menu:supply'),
  ('STATION', 'menu:station-consignment'),
  ('STATION', 'menu:transfers'),
  ('STATION', 'menu:fulfillment-orders'),
  ('STATION', 'menu:pickup-scan')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 用户：供应流通 + 履约订单/取货
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('CUSTOMER', 'menu:supply'),
  ('CUSTOMER', 'menu:fulfillment-orders'),
  ('CUSTOMER', 'menu:pickup-scan')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 平台管理员：全部新菜单码 + 权限骨架 3 页 + 商家入驻
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('PLATFORM_ADMIN', 'menu:supply'),
  ('PLATFORM_ADMIN', 'menu:production'),
  ('PLATFORM_ADMIN', 'menu:mfg-inventory'),
  ('PLATFORM_ADMIN', 'menu:station-consignment'),
  ('PLATFORM_ADMIN', 'menu:transfers'),
  ('PLATFORM_ADMIN', 'menu:fulfillment-orders'),
  ('PLATFORM_ADMIN', 'menu:pickup-scan'),
  ('PLATFORM_ADMIN', 'menu:commission-rules'),
  ('PLATFORM_ADMIN', 'menu:role-templates'),
  ('PLATFORM_ADMIN', 'menu:role-groups'),
  ('PLATFORM_ADMIN', 'menu:principal-bindings'),
  ('PLATFORM_ADMIN', 'menu:merchants')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 6) 回写 roles.grants（权限真源）：模板展开结果 ----------
-- V47 建 MANUFACTURER / STATION 时 grants 置 '{}'，非超管账号绑定后拿不到任何权限位，
-- 菜单过滤一生效就「什么都不显示」。此处按 role_template_permissions 同步，
-- 保证「绑定即授予」后菜单立即可见。幂等：每次执行都以模板当前内容覆盖。
--
-- 只覆盖 V47 新建的两个业务角色码：CUSTOMER 的 grants 是 V11 种下的
-- {"permissions":[...]} 对象结构（parseGrants 只认数组），动了会清空老用户权限。
-- 注意：roles.grants 在 V18 已从 JSONB 改为 TEXT（Role 实体以 String 映射），
--       故这里必须 ::text 后再赋值，否则 PG 报「column grants is of type text but expression is of type jsonb」。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'STATION');
