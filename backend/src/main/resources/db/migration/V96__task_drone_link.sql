SET search_path = claw;

-- ============================================================
-- P3 Task Hall：DRONE_OP 任务 ↔ 无人机作业计量关联（V96）
-- 任务发布时创建 drone_missions 记录并回写 tasks.drone_mission_id；
-- 结算仍走通用 TASK_SETTLEMENT 双记账，本迁移不涉及资金逻辑。
-- ============================================================

ALTER TABLE claw.tasks ADD COLUMN IF NOT EXISTS drone_mission_id BIGINT NULL REFERENCES drone_missions(id);
CREATE INDEX IF NOT EXISTS idx_tasks_drone_mission ON tasks (drone_mission_id);

-- 无人机资产默认具备 DRONE_OP 能力标签（V94 只覆盖了 VEHICLE/EV）
UPDATE claw.assets SET capabilities = 'DRONE_OP'
WHERE asset_type = 'DRONE' AND (capabilities IS NULL OR capabilities = '');
