-- =====================================================================
-- Claw 平台 V80 增量（④ 菜单布局后端持久化：hidden / custom 两个布局标志位）
-- 依据：菜单布局此前分层落在浏览器 localStorage（不稳定、且只属于单机），
--       现统一收敛到后端 —— claw.permissions 已是菜单结构的真源
--       （parent_code / sort_no / path / icon / name），
--       GET /api/v1/admin/permissions/mine 已据此构建侧边栏。
--       本次只补齐「布局标志位」，并配套原子批量保存接口
--       （GET/PUT /api/v1/admin/menu-layout）。
--
-- 范围：
--   1) permissions 增加 hidden BOOLEAN NOT NULL DEFAULT FALSE
--        —— 菜单布局标志：TRUE = 该菜单项在菜单管理 / 侧边栏中隐藏
--           （连同其整棵子树一起从 /permissions/mine 的菜单树中剔除）。
--   2) permissions 增加 custom BOOLEAN NOT NULL DEFAULT FALSE
--        —— 菜单布局标志：TRUE = 用户自建的菜单项。
--           批量保存（PUT /api/v1/admin/menu-layout）只回收 custom = TRUE
--           且本次未提交的记录；custom = FALSE 的内置种子菜单永不被删除，
--           避免误清掉由 Flyway 播下的菜单种子。
--
-- ⚠️ 陷阱 1：表名不带 schema 前缀 —— 本脚本顶部已 SET search_path = claw，
--    与 V25（建表）/ V79（改表）保持一致；写成 claw.permissions 反而在
--    search_path 已切换时形成重复限定，故严禁加 "claw." 前缀。
-- ⚠️ 陷阱 2：ADD COLUMN IF NOT EXISTS + NOT NULL DEFAULT FALSE —— 老库已有数据
--    一次性回填为 FALSE（全部可见、全部非自建），行为与加列前完全一致，无破坏性。
-- 幂等性：ADD COLUMN IF NOT EXISTS；二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 菜单布局：隐藏标志（hidden）----------
ALTER TABLE permissions
  ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------- 2) 菜单布局：用户自建标志（custom）----------
ALTER TABLE permissions
  ADD COLUMN IF NOT EXISTS custom BOOLEAN NOT NULL DEFAULT FALSE;
