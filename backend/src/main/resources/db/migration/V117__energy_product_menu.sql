-- =====================================================================
-- Claw 平台 V117 增量（能源运营 · 能源品类菜单收口 · 三步法）
-- 依据：前端「能源品类商品」页（ProductEnergy）需要独立菜单入口与权限位，
--       与 V116 的 pv-station / pv-trace / vpp 同属 menu:energy 分组。
--
-- 范围：
--   1) 菜单树：menu:product-energy（父 menu:energy，path /product-energy）
--   2) 为 MANUFACTURER / REGULATOR 挂载菜单码
--   3) roles.grants 回写（三步法；仅覆盖 MANUFACTURER / REGULATOR）
--
-- 红线：
--   * 绝不改写 PLATFORM_ADMIN 的通配符 grants='["*"]'（demo 管理员 ...007 依赖它全链路放行）。
--   * 全量幂等：INSERT ... ON CONFLICT DO NOTHING / DO UPDATE。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 菜单树 ----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no, icon) VALUES
  ('menu:product-energy', '能源品类商品', 'MENU', 'menu:energy', '/product-energy', 484, 'shopping')
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 角色模板挂载（厂家可见自有能源品类；监管者只读） ----------
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER', 'menu:product-energy')
ON CONFLICT (template_code, permission_code) DO NOTHING;

INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('REGULATOR', 'menu:product-energy')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 3) roles.grants 回写（三步法；覆盖 MANUFACTURER / REGULATOR，不动 PLATFORM_ADMIN 通配） ----------
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'REGULATOR');
