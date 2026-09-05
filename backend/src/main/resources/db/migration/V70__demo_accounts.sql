-- =====================================================================
-- Claw 平台 V70 增量（演示账号种子 · 验证三层权限用）
-- 依据：v3 角色已落到三层权限（V69），但 claw.users / claw.user_role_packages
--       此前无任何种子（13800000000 等账号仅为文档示例，库里不存在）。
-- 本脚本为 7+1 类角色各建一个可登录演示账号，用于验收：
--   ① 菜单按角色过滤 ② 接口按权限拦截 ③ 数据范围只返回本人数据。
-- 登录方式：短信验证码（dev 回显），无需密码。
--   1) POST /api/v1/auth/sms-code {"phone":"13800000001"}  → 取回显的 6 位码
--   2) POST /api/v1/auth/login    {"phone":"13800000001","code":"<码>"} → 取 token
--   3) GET  /api/v1/auth/me (Bearer token) → roles + permissions 按角色回显
-- 全量幂等：INSERT ... ON CONFLICT DO NOTHING / DO UPDATE；不删不改既有数据。
-- 仅插入 user_role_packages（驱动 JWT roles 声明与 effectivePermissions），
-- 并对 PLATFORM_ADMIN 额外补 user_roles（isPlatformAdmin 读此表）+ 确保其 grants 为通配。
-- 说明：MANUFACTURER/STATION 的 CUSTOM 数据范围（第三层）需真实 principal 行，
--       此处不建（避免引入厂家/服务站主体），该层在本演示种子下回落空作用域（不抛异常）。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 演示用户（仅 phone，其余列走默认值） ----------
INSERT INTO users (phone, full_name, locale) VALUES
  ('13800000001', '演示-司机',   'zh'),
  ('13800000002', '演示-飞手',   'zh'),
  ('13800000003', '演示-商家',   'zh'),
  ('13800000004', '演示-厂家',   'zh'),
  ('13800000005', '演示-服务站', 'zh'),
  ('13800000006', '演示-监管者', 'zh'),
  ('13800000007', '演示-平台管理员', 'zh'),
  ('13800000008', '演示-普通用户', 'zh')
ON CONFLICT (phone) DO NOTHING;

-- ---------- 2) 角色包（驱动 JWT roles 声明 + effectivePermissions） ----------
INSERT INTO user_role_packages (user_id, role_id, source)
SELECT u.id, r.id, 'ADMIN'
FROM users u, roles r
WHERE (u.phone, r.code) IN (
  ('13800000001','DRIVER'),
  ('13800000002','PILOT'),
  ('13800000003','MERCHANT'),
  ('13800000004','MANUFACTURER'),
  ('13800000005','STATION'),
  ('13800000006','REGULATOR'),
  ('13800000007','PLATFORM_ADMIN'),
  ('13800000008','CUSTOMER'))
ON CONFLICT (user_id, role_id) DO NOTHING;

-- ---------- 3) RBAC 层：PLATFORM_ADMIN 额外补 user_roles（isPlatformAdmin 读取） ----------
INSERT INTO user_roles (user_id, role_id, tenant_id)
SELECT u.id, r.id, 1
FROM users u, roles r
WHERE u.phone = '13800000007' AND r.code = 'PLATFORM_ADMIN'
ON CONFLICT (user_id, role_id) DO NOTHING;

-- ---------- 4) 平台管理员持通配权限（V40 在真实 PG 已置，此处兜底幂等） ----------
UPDATE roles SET grants = '["*"]'
 WHERE code = 'PLATFORM_ADMIN' AND (grants IS NULL OR grants NOT LIKE '%*%');
