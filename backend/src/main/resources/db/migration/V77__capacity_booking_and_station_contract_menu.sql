-- =====================================================================
-- Claw 平台 V77 增量（容量预订 + 服务站合约：补齐菜单与权限种子）
-- 依据：用户 2026-09-06 反馈「容量预定入口看不到」+ 同源缺陷「station-contracts 同样不在 NAV」
--
-- 根因：后端 domain/capacity 与 AdminCapacityController / StationContracts 页面、路由
--       （App.jsx:128 / :129）早已就绪，但 permissions 表没有对应 menu:* 节点，
--       导致前端 NAV 配置里没有这两项、侧边栏不可见，直接访问 URL 因 AdminLayout 的
--       menu:{key} 单层守卫而 403。
--
-- 本迁移只做幂等种子（三步法，沿用 V67 / V68 范式），无任何 DDL。
--   1) menu:capacity-booking（供应流通组，生产容量预订；path=/capacity-booking）
--   2) menu:station-contracts（服务站组；path=/station-contracts）
--   3) 资源（BUTTON）权限码 station-contract:manage（AdminStationContractController
--      端点 @RequirePermission("station-contract:manage") 校验位；不补种则非超管写接口 403）
--   4) 角色模板挂载（MANUFACTURER / STATION / PLATFORM_ADMIN）
--   5) roles.grants 回写（仅覆盖 MANUFACTURER / STATION）
--
-- ⚠️ 陷阱 1：PLATFORM_ADMIN 已靠 V40 种下的 '["*"]' 通配符拥有全部权限位，
--    不在第 5 步回写范围内（否则会把超管从「通配」降级为「白名单」）。
-- ⚠️ 陷阱 2：CUSTOMER 的 grants 是 V11 种下的对象结构，parseGrants 只认数组，
--    动了会清空老用户权限 —— 第 5 步严格限定 MANUFACTURER / STATION，绝不碰 CUSTOMER。
-- ⚠️ 陷阱 3：roles.grants 在 V18 已从 JSONB 改为 TEXT，回写时必须 ::text。
-- ⚠️ 陷阱 4：permissions 表有 icon 列（可空），沿用 V62 的 6 列写法。
-- 幂等性：INSERT 带 ON CONFLICT DO NOTHING；UPDATE 按 code 限定；二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 容量预订菜单码（供应流通组，sort_no=458，接在提成规则 457 之后）----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:capacity-booking', '容量预订', 'MENU', 'menu:supply', '/capacity-booking', 458)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 服务站合约菜单码（服务站组，sort_no=4，接在服务站结算 3 之后）----------
INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:station-contracts', '服务站合约', 'MENU', 'menu:station', '/station-contracts', 4)
ON CONFLICT (code) DO NOTHING;

-- ---------- 3) 资源（BUTTON）权限码：端点 @RequirePermission 位 ----------
-- 容量预订两端（CapacityController / AdminCapacityController）无 @RequirePermission 注解，
-- 仅受 menu:capacity-booking 单层菜单守卫控制，无需补 BUTTON 位。
-- 服务站合约端点 @RequirePermission("station-contract:manage")，必须补种。
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('station-contract:manage', '服务站合约管理', 'BUTTON', 'menu:station', 50)
ON CONFLICT (code) DO NOTHING;

-- ---------- 4) 角色模板挂载（厂家 / 服务站 / 平台管理员）----------
-- 菜单码
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER',    'menu:capacity-booking'),
  ('STATION',         'menu:capacity-booking'),
  ('PLATFORM_ADMIN',  'menu:capacity-booking'),
  ('STATION',         'menu:station-contracts'),
  ('PLATFORM_ADMIN',  'menu:station-contracts')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 资源权限码（服务站合约管理：服务站自身可发起/退出，平台管理员统管）
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('STATION',         'station-contract:manage'),
  ('PLATFORM_ADMIN',  'station-contract:manage')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 5) 回写 roles.grants（权限真源）—— 沿用 V54 / V62 / V67 / V68 模式 ----------
-- 只覆盖业务角色码；PLATFORM_ADMIN 走通配符（见陷阱 1）；
-- CUSTOMER 是对象结构，禁止纳入（见陷阱 2）。
-- 此时第 1~4 步均已落库，回写会把新增的 menu:/station-contract:* 一并下发给已绑定账号。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'STATION');
