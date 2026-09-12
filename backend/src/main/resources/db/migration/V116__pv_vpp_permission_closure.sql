-- =====================================================================
-- Claw 平台 V116 增量（光伏 / 虚拟电厂 权限收口 · 三步法）
-- 依据：光伏数据链路 / 追溯 / VPP 聚合调度三批切片已落地（V104~V114），
--       三个后端 Controller 此前刻意「不加权限注解」以便统一收口，本脚本完成收口。
--
-- 范围：
--   1) 能源运营菜单树：menu:energy（根）→ menu:pv-station / menu:pv-trace / menu:vpp
--   2) 按钮权限位（仅 POST 写接口，遵循项目约定「读接口 GET 一律不动」）：
--        pv:telemetry:push  —— POST /api/v1/pv/telemetry（数采器联调/Mock 灌入）
--        vpp:dispatch      —— POST /api/v1/vpp/{id}/dispatch-plan（生成调度建议/指令）
--      （GET 类：capacity / orders / trace/* 均为只读，按约定不加注解，保持开放给已鉴权用户）
--   3) 为 MANUFACTURER / REGULATOR 挂载菜单码（厂家持有光伏资产可见；监管者只读 ALL）
--   4) roles.grants 回写（沿用 V68/V69 三步法，覆盖 MANUFACTURER / REGULATOR）
--
-- 红线：
--   * 绝不改写 PLATFORM_ADMIN 的通配符 grants='["*"]'（demo 管理员 ...007 依赖它全链路放行）。
--   * 全量幂等：INSERT ... ON CONFLICT DO NOTHING / DO UPDATE。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 能源运营菜单树 ----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no, icon) VALUES
  ('menu:energy',     '能源运营',   'MENU', NULL,         NULL,         48,  'thunderbolt'),
  ('menu:pv-station', '光伏电站',   'MENU', 'menu:energy', '/pv-station', 481, 'sun'),
  ('menu:pv-trace',   '光伏追溯',   'MENU', 'menu:energy', '/pv-trace',   482, 'apartment'),
  ('menu:vpp',        '虚拟电厂',   'MENU', 'menu:energy', '/vpp',        483, 'cluster')
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 按钮权限位（POST 写接口） ----------
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('pv:telemetry:push', '光伏遥测上报', 'BUTTON', 'menu:pv-station', 1),
  ('vpp:dispatch',      'VPP调度指令',  'BUTTON', 'menu:vpp',        1)
ON CONFLICT (code) DO NOTHING;

-- ---------- 3) 角色模板挂载（菜单码；厂家可见自有光伏资产，监管者只读） ----------
-- 厂家 MANUFACTURER：光伏资产的生产/持有方，可见电站监控 + 追溯 + 虚拟电厂聚合看板。
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER', 'menu:pv-station'),
  ('MANUFACTURER', 'menu:pv-trace'),
  ('MANUFACTURER', 'menu:vpp')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 监管者 REGULATOR：只读 ALL，可见全部能源运营看板（无写/导出权限位）。
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('REGULATOR', 'menu:pv-station'),
  ('REGULATOR', 'menu:pv-trace'),
  ('REGULATOR', 'menu:vpp')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 4) roles.grants 回写（三步法；覆盖 MANUFACTURER / REGULATOR，不动 PLATFORM_ADMIN 通配） ----------
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'REGULATOR');
