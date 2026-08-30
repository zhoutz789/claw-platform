-- V46：权限码约定清理（permission-code convention cleanup）。
--
-- 本脚本仅做「约定对齐 + 历史包袱清理」，不新增任何业务权限点，也不修改 Java / 前端。
-- 全脚本幂等、可重复运行（DELETE 固定 IN 列表 / DROP CONSTRAINT IF EXISTS / COMMENT ON COLUMN）。
--
-- ============ 规范（canonical permission-code convention）============
-- 1) 按钮 / 动作权限点：{resource}:{action}
--        action 集合（按资源适用）：create / update / delete / export / import
--        示例：asset:export、order:refund、user:freeze、role:update、menu:update
--        与后端 @RequirePermission 注解码、前端 <Perm> / @RequirePermission 引用一一对应。
-- 2) 菜单权限点：menu:{navKey}（ptype = MENU），严格镜像 web/src/nav.js 的菜单树。
-- 3) 遗留的 btn:* 前缀风格（V25 示例种子）违反上述约定，由本脚本删除。
--
-- ============ 特别说明（记录给后续开发者）============
-- 菜单配置页（web/src/pages/MenuManager.jsx）的「导入」按钮使用 <Perm code="menu:update"> 包裹，
-- 即 IMPORT 由 menu:update 覆盖（menu:update 已由 V45 种子）；因此【刻意不】存在独立的 menu:import
-- 权限码。请勿为「菜单导入」再新增 menu:import 孤儿权限点，以免与 menu:update 语义冲突。
-- （同一约定下，按钮 action 集合包含 import，但 menu 资源当前只对 update 落地了 UI 控制点。）
SET search_path = claw;

-- ---------- 1) 删除 V25 遗留的 btn:* 遗留权限码（违反 {resource}:{action} 约定）----------
-- V40 已用等价的规范码 seed：asset:export / order:refund / user:freeze，因此删除安全，
-- 不会破坏任何 <Perm>/@RequirePermission 引用（grep 已确认前后端均无 btn:asset:export 等引用）。
-- 因 role_permissions.permission_code REFERENCES permissions(code) ON DELETE CASCADE，
-- 此处删除会级联清理这些码冗余的 role_permissions 行（无害：等价规范码已存在）。
-- DELETE 使用固定 IN 列表：已删除的行再次删除为 no-op，故幂等。
DELETE FROM permissions
WHERE code IN ('btn:asset:export', 'btn:order:refund', 'btn:user:freeze');

-- ---------- 2) 对齐 roles.data_scope 注释 + 增加 CHECK 约束（兜底 6 个后端支持值）----------
-- V25 的 data_scope 列注释仅列了 SELF/DEPARTMENT/ALL/TYPE 4 个值，已过时；
-- 后端 common.security.DataScopeResult.Scope 枚举及 DataScopeService.mapScope 实际支持 6 个值。
-- COMMENT ON COLUMN 幂等；DROP CONSTRAINT IF EXISTS + ADD CONSTRAINT 在 Postgres 上幂等。
COMMENT ON COLUMN roles.data_scope IS '数据范围：SELF/DEPARTMENT/DEPARTMENT_AND_BELOW/CUSTOM/TYPE/ALL（与 common.security.DataScopeResult.Scope 枚举一致）';

ALTER TABLE roles DROP CONSTRAINT IF EXISTS chk_roles_data_scope;
ALTER TABLE roles ADD CONSTRAINT chk_roles_data_scope
  CHECK (data_scope IN ('SELF', 'DEPARTMENT', 'DEPARTMENT_AND_BELOW', 'CUSTOM', 'TYPE', 'ALL'));
