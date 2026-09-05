-- =====================================================================
-- Claw 平台 V74 增量（合约续签 · 周老板 2026-09-06 拍板缺口①）
-- 依据：服务站追加保证金升档 → 旧约续签为新约（RENEWED 旧约 + 新 ACTIVE 3 年期）。
-- 问题：V73 用全量 UNIQUE(station_id, status)，多次升档会产生多份 RENEWED 而冲突。
-- 修复：删除全量唯一约束，改为「部分唯一索引」——仅约束进行中状态
--       (ACTIVE / EXIT_REQUESTED)，终态 (RENEWED / EXITED / EXPIRED / PENDING) 允许多份。
-- 注意：不修改 V73（已应用迁移改内容会触发 Flyway 校验失败），本迁移在其后运行。
-- =====================================================================

SET search_path = claw;

-- 删除 V73 的全量唯一约束（多次升档的 RENEWED 会与之冲突）
ALTER TABLE station_contracts
    DROP CONSTRAINT IF EXISTS uq_station_contracts_station_status;

-- 部分唯一索引：仅进行中状态互斥（同站仅一份 ACTIVE / 一份 EXIT_REQUESTED）
CREATE UNIQUE INDEX IF NOT EXISTS uq_station_contracts_active
    ON station_contracts (station_id, status)
    WHERE status IN ('ACTIVE', 'EXIT_REQUESTED');
