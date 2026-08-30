-- V43：部门菜单 / 按钮权限种子（收尾 T01 补完缺口）。
-- 补齐 V40 遗漏的 menu:departments（部门管理入口）与 department:create / department:update（写接口权限点），
-- 使非降级、非超管模式下，前端按「后端权威菜单」过滤时「部门管理」仍可见、写接口受控。
-- 与 AdminDepartmentController 的 @RequirePermission("department:create" / "department:update") 一一对应。
-- 超管角色 grants 已是 ["*"]，无需再单独授权。
-- 全量幂等：INSERT ... ON CONFLICT (code) DO NOTHING。
SET search_path = claw;

INSERT INTO permissions (code, name, ptype, parent_code, path, sort_no) VALUES
  ('menu:departments', '部门管理', 'MENU', 'menu:sys', '/departments', 991),
  ('department:create', '新建部门', 'BUTTON', NULL, NULL, 1),
  ('department:update', '编辑部门', 'BUTTON', NULL, NULL, 2)
ON CONFLICT (code) DO NOTHING;
