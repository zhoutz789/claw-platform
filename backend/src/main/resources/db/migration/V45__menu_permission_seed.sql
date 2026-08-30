-- V45：补齐「菜单管理」按钮权限点种子。
--
-- QA 通电验证发现：前端 web/src/pages/MenuManager.jsx 用 <Perm code="menu:update"> 包裹
-- 保存类 UI（共 7 处），但 V40 的「资源 × 动作」按钮种子列表（asset/customer-order/...）
-- 漏掉了 'menu' 这个资源，导致 menu:create/update/delete/export 从未被 seed；
-- 非超管用户无法被授予该权限点，菜单保存 UI 实际不可达 / 不可强制执行。
--
-- 此处补齐 menu:* 四个动作按钮点（与 V40 的 {资源}:{动作} 约定一致），
-- parent_code 挂在 menu:menu-manager 之下，遵循「严格镜像 V40/V43 风格」：
--   SET search_path = claw;  +  INSERT ... ON CONFLICT (code) DO NOTHING（幂等）。
-- 注意：permissions 表列名为 name（V25 建表 + Permission 实体），并非 name_i18n
--       （name_i18n 仅存在于 roles 表），故此处用 name。
--
-- 不改 V40（Flyway 校验和会失败），本脚本从 V45 起独立补齐。
SET search_path = claw;

-- 菜单管理按钮权限点：{资源}:{动作}，与前端 <Perm code="menu:update"> 及 @RequirePermission 码对应。
INSERT INTO claw.permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:create',  '菜单新建', 'BUTTON', 'menu:menu-manager', NULL, 961),
  ('menu:update',  '菜单保存', 'BUTTON', 'menu:menu-manager', NULL, 962),
  ('menu:delete',  '菜单删除', 'BUTTON', 'menu:menu-manager', NULL, 963),
  ('menu:export',  '菜单导出', 'BUTTON', 'menu:menu-manager', NULL, 964)
ON CONFLICT (code) DO NOTHING;
