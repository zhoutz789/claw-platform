-- =====================================================================
-- Claw 平台 V136 增量（清分 / 资金后台权限种子 · T10）
-- 依据：增量设计-资金路由与清分-v1 §10（后台入口权限位）。
-- 范围：
--   · permissions：新增 4 个按钮级权限码，挂在既有父码 menu:finance 下
--     （menu:finance 于 V25 定义，V55 重挂为财务管理根菜单，本增量不改既有权限）
--       - finance:clearing:view   清分查看（规则/指令/批次/差错列表与详情）
--       - finance:clearing:manage 清分管理（批次汇总/审核/下发、差错处置）
--       - finance:funds:view      资金查看（托管点位/虚拟子户总览）
--       - finance:funds:manage    资金管理（开虚拟子户）
-- 全量幂等：INSERT ... ON CONFLICT (code) DO NOTHING；不触碰任何既有权限码。
-- =====================================================================

SET search_path = claw;

INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('finance:clearing:view',   '清分查看', 'BUTTON', 'menu:finance', 1),
  ('finance:clearing:manage', '清分管理', 'BUTTON', 'menu:finance', 2),
  ('finance:funds:view',      '资金查看', 'BUTTON', 'menu:finance', 3),
  ('finance:funds:manage',    '资金管理', 'BUTTON', 'menu:finance', 4)
ON CONFLICT (code) DO NOTHING;
