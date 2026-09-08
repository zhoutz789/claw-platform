-- =====================================================================
-- Claw 平台 V82 增量（寄售入库发起方改造：厂家分拨 → 服务站自主入库）
-- 依据：业务方反馈「厂家生产产生的库存上有…服务站入库…我想不应该由厂家分拨到站，
--       谁操作数据是谁的，谁的数据是谁的，没必要选择某些站点，这样数据极易公开，
--       还有误操作的现象，设计上很不合理。只有系统可能需要详细报表那要单独选择展示
--       就行了，他也不能操作数据。」
--       → 寄售入库（建立 consignment_custodies 占有权）改由服务站自己发起，
--         厂家侧只保留只读库存视图。
--
-- 配套代码改动（同批次，不在本迁移内）：
--   1) 下线 POST /api/v1/admin/inventory/ship（AdminInventoryController）+ ShipReq DTO；
--   2) 新增 POST /api/v1/station/consignment/inbound（StationConsignmentController），
--      @RequirePermission("station:consignment:inbound")，站点 ID 由登录站长作用域带出、
--      厂家 ID 由 inventory.owner_manufacturer_id（货权方）带出，均不来自入参。
--
-- 本迁移只做幂等种子（两步法，沿用 V67 / V68 / V77 范式），无任何 DDL：
--   1) 资源（BUTTON）权限码 station:consignment:inbound（端点 @RequirePermission 校验位；
--      不补种则服务站账号一上来就 403）；
--   2) 角色模板挂载（STATION / PLATFORM_ADMIN —— 厂家一律不发，见「为何不给厂家」）；
--   3) roles.grants 回写（仅覆盖 STATION）。
--
-- ⚠️ 陷阱 1：表名不带 schema 前缀 —— 本脚本顶部已 SET search_path = claw，
--    与 V25（建表）/ V79 / V80（改表）保持一致；写成 claw.permissions 反而在
--    search_path 已切换时形成重复限定，故严禁加 "claw." 前缀。
-- ⚠️ 陷阱 2：PLATFORM_ADMIN 已靠 V40 种下的 '["*"]' 通配符拥有全部权限位，
--    不在第 3 步回写范围内（否则会把超管从「通配」降级为「白名单」）。
-- ⚠️ 陷阱 3：CUSTOMER 的 grants 是 V11 种下的对象结构，parseGrants 只认数组，
--    动了会清空老用户权限 —— 第 3 步严格限定 STATION，绝不碰 CUSTOMER / MANUFACTURER。
-- ⚠️ 陷阱 4：roles.grants 在 V18 已从 JSONB 改为 TEXT，回写时必须 ::text。
-- ⚠️ 陷阱 5：permissions 表有 icon 列（可空），沿用 V62 / V77 的省略写法。
--
-- 为何不给厂家发这个权限位：本次改造的核心就是「厂家不能替服务站操作数据」。
-- 厂家仍保留 menu:station-consignment（V54 种子）用于只读报表，但拿不到这个
-- 写权限位，即使旧前端残留调用也会 403。
-- 幂等性：INSERT 带 ON CONFLICT DO NOTHING；UPDATE 按 code 限定；二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 资源（BUTTON）权限码：端点 @RequirePermission 位 ----------
-- 挂载在服务站寄售菜单 menu:station-consignment（V54 已种，STATION 角色模板已具备该菜单）
-- 之下；sort_no=60 处在 V77 给 menu:station 挂的 station-contract:manage(50) 之后，
-- 与既有按钮码不冲突。
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('station:consignment:inbound', '寄售入库', 'BUTTON', 'menu:station-consignment', 60)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 角色模板挂载（服务站自身 + 平台管理员统管）----------
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('STATION',         'station:consignment:inbound'),
  ('PLATFORM_ADMIN',  'station:consignment:inbound')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 3) 回写 roles.grants（权限真源）—— 沿用 V54 / V62 / V67 / V68 / V77 模式 ----------
-- 只覆盖 STATION：本次唯一变更的角色模板就是 STATION。
-- PLATFORM_ADMIN 走通配符（见陷阱 2）；CUSTOMER 是对象结构（见陷阱 3）；
-- MANUFACTURER 本次未变更任何模板行，回写无意义且徒增风险，故不纳入。
-- 此时第 1~2 步均已落库，回写会把新增的 station:consignment:inbound
-- 一并下发给已绑定服务站主体的存量账号。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('STATION');
