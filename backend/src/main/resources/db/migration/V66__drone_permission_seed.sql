-- =====================================================================
-- Claw 平台 V66 增量（无人机 / 低空域 · 权限码种子）
-- 依据：增量设计-无人机低空域前端对接.md §3.6，做法严格对齐 V62
--
-- 四步走：
--   1) 5 个 menu:* 菜单码（只建页面不建 menu 码 → 真实后端下菜单不显示，
--      V54 已修过的同类缺陷，务必避免重犯）
--   2) 6 个 drone:* 按钮码（ptype='BUTTON'，parent_code 挂对应 menu）
--   3) 角色模板挂载（PLATFORM_ADMIN / STATION / MANUFACTURER）
--   4) roles.grants 回写
--
-- ⚠️ 陷阱 1（U5 结论）：roles.grants 回写**绝不能纳入 PLATFORM_ADMIN**。
--     V40 已把所有 auto_grant = FALSE 的平台固定角色 grants 置为 '["*"]' 通配符，
--     PLATFORM_ADMIN 走通配符天然拥有全部权限位。若在此把它一并回写成
--     按模板聚合出的有限数组，等于把超管从「通配」降级为「白名单」——
--     任何尚未挂进模板的新权限码都会让超管静默失权，且极难排查。
--     故第 4 步只覆盖业务角色码，与 V62 一致。
-- ⚠️ 陷阱 2：CUSTOMER 的 grants 是 V11 种下的 {"permissions":[...]} 对象结构，
--     parseGrants 只认数组，动了会清空老用户权限（V62 踩过）—— 禁止纳入。
-- ⚠️ 陷阱 3：roles.grants 在 V18 已从 JSONB 改为 TEXT，回写时必须 ::text。
-- ⚠️ 陷阱 4：permissions 表有 icon 列（V40 用 7 列、V62 用 6 列均成功），
--     说明 icon 可空。本脚本沿用 V62 的 6 列写法，保持一致。
--
-- 幂等性：全部 INSERT 带 ON CONFLICT DO NOTHING，UPDATE 按 code 限定，
--         二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 低空运营分组 + 4 个页面菜单码 ----------
-- sort_no 47 的依据（已核实 V40 / V54 / V62）：menu:goods=40、menu:supply=45、
-- menu:onboarding=48、menu:task=50。47 落在 supply(45) 与 onboarding(48) 之间，
-- 与 PRD「在 supply 与 task 之间」一致；前端 nav.js 的 drone 分组同样插在
-- supply 之后、task 之前。
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:drone',            '低空运营',       'MENU', NULL,          NULL,                 47),
  ('menu:airspace-zones',   '空域管理',       'MENU', 'menu:drone',  '/airspace-zones',   471),
  ('menu:flight-plans',     '飞行计划',       'MENU', 'menu:drone',  '/flight-plans',     472),
  ('menu:pilot-licenses',   '飞手资质',       'MENU', 'menu:drone',  '/pilot-licenses',   473),
  ('menu:drone-ops',        '作业与安全管控', 'MENU', 'menu:drone',  '/drone-ops',        474)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 6 个按钮级权限码 ----------
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('drone:zone:create',      '新增空域',     'BUTTON', 'menu:airspace-zones', 1),
  ('drone:flightplan:create','提交飞行计划', 'BUTTON', 'menu:flight-plans',   1),
  ('drone:license:create',   '登记飞手资质', 'BUTTON', 'menu:pilot-licenses', 1),
  ('drone:mission:create',   '登记作业',     'BUTTON', 'menu:drone-ops',      1),
  ('drone:safety:simulate',  '触发锁机',     'BUTTON', 'menu:drone-ops',      2),
  ('drone:safety:resolve',   '解除锁机',     'BUTTON', 'menu:drone-ops',      3)
ON CONFLICT (code) DO NOTHING;

-- ---------- 3.1) 平台管理员：全部 11 个 ----------
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('PLATFORM_ADMIN', 'menu:drone'),
  ('PLATFORM_ADMIN', 'menu:airspace-zones'),
  ('PLATFORM_ADMIN', 'menu:flight-plans'),
  ('PLATFORM_ADMIN', 'menu:pilot-licenses'),
  ('PLATFORM_ADMIN', 'menu:drone-ops'),
  ('PLATFORM_ADMIN', 'drone:zone:create'),
  ('PLATFORM_ADMIN', 'drone:flightplan:create'),
  ('PLATFORM_ADMIN', 'drone:license:create'),
  ('PLATFORM_ADMIN', 'drone:mission:create'),
  ('PLATFORM_ADMIN', 'drone:safety:simulate'),
  ('PLATFORM_ADMIN', 'drone:safety:resolve')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 3.2) 服务站：作业与安全管控全开，合规类归平台 ----------
-- 不给 zone / license / flightplan 的 create 权：空域与资质是合规事项，
-- 按设计归平台侧统一维护；服务站只做日常作业登记与安全处置。
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('STATION', 'menu:drone'),
  ('STATION', 'menu:airspace-zones'),
  ('STATION', 'menu:flight-plans'),
  ('STATION', 'menu:pilot-licenses'),
  ('STATION', 'menu:drone-ops'),
  ('STATION', 'drone:mission:create'),
  ('STATION', 'drone:safety:simulate'),
  ('STATION', 'drone:safety:resolve')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 3.3) 厂家：只读（0 个 button） ----------
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER', 'menu:drone'),
  ('MANUFACTURER', 'menu:airspace-zones'),
  ('MANUFACTURER', 'menu:pilot-licenses'),
  ('MANUFACTURER', 'menu:drone-ops')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- CUSTOMER / MERCHANT：不授予（与 V62 一致，保持沉默即拒绝）

-- ---------- 4) 回写 roles.grants（权限真源）—— 沿用 V62 模式 ----------
-- 只覆盖业务角色码；PLATFORM_ADMIN 走 V40 种下的 '["*"]' 通配符，不在此列（见陷阱 1）；
-- CUSTOMER 是对象结构，禁止纳入（见陷阱 2）。
-- 该 UPDATE 会覆盖这三个角色的现有 grants —— 这是预期行为（把新增的 drone:* 码
-- 追加进已绑定账号的权限集），与 V54 / V62 一致。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'STATION', 'MERCHANT');
