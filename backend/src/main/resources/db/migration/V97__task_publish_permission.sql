-- =====================================================================
-- Claw 平台 V97 增量（任务大厅：发布权限位 task:publish 注册）
-- 依据：Phase 3 收口遗留项 —— TaskController 全类此前仅做登录校验（uid()），
--       未挂任何 @RequirePermission，任何已登录用户都能 POST /api/v1/tasks
--       发布任务。发布是资金发起动作（发布方账户须有余额，报酬由发布方
--       账户 D 出，不足会返回 42251），必须收敛到权限位。
--
-- 本迁移只做幂等种子（三步法，沿用 V82 / V77 / V68 范式），无任何 DDL：
--   1) 资源（BUTTON）权限码 task:publish（端点 @RequirePermission 校验位；
--      不补种则目标角色一上来就 403）；
--   2) 角色模板挂载（DRIVER / PILOT / MERCHANT —— 当前持有任务菜单的三类业务角色）；
--   3) roles.grants 回写（仅覆盖第 2 步命中的角色）。
--
-- ⚠️ 陷阱 1：表名不带 schema 前缀 —— 本脚本顶部已 SET search_path = claw，
--    与 V25（建表）/ V80 / V82（改表）保持一致；严禁加 "claw." 前缀。
-- ⚠️ 陷阱 2：PLATFORM_ADMIN 已靠 V40 种下的 '["*"]' 通配符拥有全部权限位，
--    不入第 2 步、不在第 3 步回写范围内（否则会把超管从「通配」降级为「白名单」）。
-- ⚠️ 陷阱 3：CUSTOMER 的 grants 是 V11 种下的对象结构，parseGrants 只认数组，
--    动了会清空老用户权限 —— 第 3 步严格限定三个角色，绝不碰 CUSTOMER。
-- ⚠️ 陷阱 4：roles.grants 在 V18 已从 JSONB 改为 TEXT，回写时必须 ::text。
-- ⚠️ 陷阱 5：permissions 表有 icon 列（可空），沿用 V82 的省略写法。
--
-- 为何不给 REGULATOR：REGULATOR 虽持有全部 menu:task* 菜单（V69:162-167），
-- 但其语义是「监管可查看全部任务」，不参与发布 —— 发布权是资金发起动作。
-- 若监管确需发布，另行授权即可（本迁移不预先扩权）。
-- 为何不给 STATION / MANUFACTURER / CUSTOMER：三者当前均不持有任何 menu:task* 菜单。
-- 幂等性：INSERT 带 ON CONFLICT DO NOTHING；UPDATE 按 code 限定；二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 资源（BUTTON）权限码：端点 @RequirePermission 位 ----------
-- 挂在任务发布菜单 menu:task（V40:20 已种）之下。ptype='BUTTON' 不会进入导航树
-- （AdminPermissionController.myMenu() 只收 MENU 节点），仅用于权限目录/矩阵展示。
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('task:publish', '发布任务', 'BUTTON', 'menu:task', 50)
ON CONFLICT (code) DO NOTHING;

-- ---------- 2) 角色模板挂载 ----------
-- DRIVER：物流 / 租赁任务；PILOT：无人机作业任务；MERCHANT：广告任务。
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('DRIVER',   'task:publish'),
  ('PILOT',    'task:publish'),
  ('MERCHANT', 'task:publish')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 3) 回写 roles.grants（权限真源）----------
-- 只覆盖本次变更的三个角色模板：PermissionService.effectivePermissions() 实际消费
-- 的是 roles.grants 列（RTEXT 数组），漏掉这一步端点对目标角色仍会 403。
-- PLATFORM_ADMIN 走通配符（陷阱 2），CUSTOMER 是对象结构（陷阱 3），
-- REGULATOR 本次未授权故不回写。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('DRIVER', 'PILOT', 'MERCHANT');
