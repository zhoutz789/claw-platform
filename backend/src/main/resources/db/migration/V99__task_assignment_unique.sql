-- =====================================================================
-- Claw 平台 V99 增量（任务大厅：一任务一接单的 DB 层兜底约束）
-- 依据：Phase 3 收口遗留项。应用层 TaskService.accept() 已改用
--       taskRepository.findByIdForUpdate()（PESSIMISTIC_WRITE 行锁）串行化接单，
--       并以 task.status != OPEN → 40901 保证同一任务只会产生一条 assignment。
--       但这只是「应用层 + 行锁」的保证：一旦行锁被降级或绕过（例如未来改回
--       无锁读取、悲观锁驱动不支持、或跨实例未落在同一事务），并发仍可插入
--       两条 assignment，进而一任务被两次 settle —— 双结算，账本出现重复
--       分录，资金直接错。本迁移在 DB 层加唯一约束作为最后一道防线。
--
-- 语义前提：任务大厅当前模型是「一任务一接单」（accept 后任务即离开 OPEN，
--   且 TaskStatus 终态 SETTLED / CANCELLED 不可逆，无回退/重开路径）。
--   若未来引入「取消后重新招募」，须先把本约束改为部分唯一索引，例如：
--     CREATE UNIQUE INDEX ... ON task_assignments (task_id) WHERE status <> 'CANCELLED';
--
-- 幂等性：PostgreSQL 的 ADD CONSTRAINT 不支持 IF NOT EXISTS，故先
--   DROP CONSTRAINT IF EXISTS 再 ADD，保证二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- 一任务一接单：DB 层兜底（应用层已用悲观锁串行化，此处是双保险）
ALTER TABLE task_assignments DROP CONSTRAINT IF EXISTS uq_task_assignments_task;
ALTER TABLE task_assignments
  ADD CONSTRAINT uq_task_assignments_task UNIQUE (task_id);

-- 唯一约束已隐式建立 task_id 唯一索引，V93 建的非唯一索引 idx_ta_task 完全重复。
-- 删除以减少每次写入的索引维护开销（唯一索引同样可服务按 task_id 的查询）。
DROP INDEX IF EXISTS idx_ta_task;
