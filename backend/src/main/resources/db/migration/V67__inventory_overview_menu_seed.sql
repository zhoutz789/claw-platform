-- =====================================================================
-- Claw 平台 V67 增量（模块三 · 供应流通库存改造：库存总览菜单权限种子）
-- 依据：增量设计-模块三-库存改造.md §C.7 / B.1
--
-- 只做幂等种子（INSERT ... ON CONFLICT DO NOTHING + 一条幂等 UPDATE roles.grants），
-- 无任何 DDL、不建任何表（模块三硬约束：零新表）。
--
-- 四步走：
--   1) 1 个 menu:* 菜单码：inventory-overview（库存总览，排序 450 天然排在
--      production(451) 之前，作为「供应流通」组首位默认入口）
--   2) 角色模板挂载（MANUFACTURER / STATION / PLATFORM_ADMIN）
--   3) roles.grants 回写（沿用 V54 / V62 模式）
--
-- ⚠️ 陷阱 1（与 V66 一致）：PLATFORM_ADMIN 已靠 V40 种下的 '["*"]' 通配符拥有全部权限位，
--    不在第 3 步回写范围内（否则会把超管从「通配」降级为「白名单」）。
-- ⚠️ 陷阱 2：CUSTOMER 的 grants 是 V11 种下的对象结构，parseGrants 只认数组，
--    动了会清空老用户权限 —— 第 3 步严格限定 MANUFACTURER / STATION，绝不碰 CUSTOMER。
-- ⚠️ 陷阱 3：roles.grants 在 V18 已从 JSONB 改为 TEXT，回写时必须 ::text。
-- ⚠️ 陷阱 4：permissions 表有 icon 列（可空），沿用 V62 的 6 列写法。
--
-- 幂等性：INSERT 带 ON CONFLICT DO NOTHING；UPDATE 按 code 限定；二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 库存总览菜单码（供应流通组首位，sort_no=450）----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:inventory-overview', '库存总览', 'MENU', 'menu:supply', '/inventory-overview', 450)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 角色模板挂载（厂家 / 服务站 / 平台管理员）----------
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER',    'menu:inventory-overview'),
  ('STATION',         'menu:inventory-overview'),
  ('PLATFORM_ADMIN',  'menu:inventory-overview')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 3) 回写 roles.grants（权限真源）—— 沿用 V54 / V62 模式 ----------
-- 只覆盖业务角色码；PLATFORM_ADMIN 走通配符（见陷阱 1）；
-- CUSTOMER 是对象结构，禁止纳入（见陷阱 2）。该 UPDATE 会覆盖这两个角色的现有 grants，
-- 把新增的 menu:inventory-overview 追加进已绑定账号的权限集，与 V54 / V62 一致。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'STATION');
