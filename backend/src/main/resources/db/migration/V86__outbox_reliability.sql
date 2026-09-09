-- =====================================================================
-- Claw 平台 V86 增量（Outbox 可靠性改造：状态机 + 重试退避 + 死信 + 处理租约）
--
-- 背景：claw.outbox_events（V50:75-85）只有 published 布尔位，OutboxRelay
--       2 秒全量轮询 published=FALSE 的行，失败仅 log.warn，无重试上限 /
--       无退避 / 无死信 / 无人工重投入口 / 无多实例互斥。配合
--       @TransactionalEventListener（AFTER_COMMIT）使用时会「事务已提交 +
--       published=true 已落库，监听器抛异常只被 Spring 记日志」→ 事件永久
--       丢失且无任何痕迹。履约结算是资金链路，静默丢事件 = 用户冻结资金
--       永不释放 + 厂家收不到款。
--
-- 范围（只加列 / 索引 / 约束，不删列、不改既有语义）：
--   1) outbox_events 增加 8 个可靠性列
--   2) 存量数据回填（published = TRUE → PUBLISHED，其余 → NEW）
--   3) 认领 / 死信两个部分索引
--   4) status 取值 CHECK
--
-- ⚠️ 陷阱 1：表名不带 schema 前缀 —— 本脚本顶部已 SET search_path = claw，
--    与 V50（建表）/ V80（改表）保持一致；写成 claw.outbox_events 反而在
--    search_path 已切换时形成重复限定，故严禁加 "claw." 前缀。
-- ⚠️ 陷阱 2：ADD COLUMN ... NOT NULL DEFAULT —— PostgreSQL 11+ 为元数据级
--    默认值变更，不重写表，大表也安全；存量行一次性取默认值，无破坏性。
-- ⚠️ 陷阱 3：status 一律由 OutboxRelay 驱动，published 只做向后兼容双写
--    （OutboxPublisher 与存量代码仍在写 published），查询以 status 为准。
--
-- 幂等性：ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS /
--         DROP CONSTRAINT IF EXISTS + ADD CONSTRAINT；二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 可靠性列 ----------
-- status：NEW 待投 / FAILED 待重试 / PUBLISHED 成功 / DEAD 死信（人工介入）
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS status          VARCHAR(16)  NOT NULL DEFAULT 'NEW';
-- retry_count：已尝试次数（认领时 +1，进程崩溃也计次，防无限重投）
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS retry_count     INT          NOT NULL DEFAULT 0;
-- max_attempts：重试上限，可按事件人工调大后重投
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS max_attempts    INT          NOT NULL DEFAULT 5;
-- next_attempt_at：下次可投时间（退避落点）
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now();
-- last_error：最后一次异常摘要（类名 + message + 堆栈首行，截断 2000 字符）
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS last_error      TEXT;
-- locked_until：处理租约到期时间（进程崩溃后自动可回收，无需 reaper 定时任务）
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS locked_until    TIMESTAMPTZ;
-- locked_by：实例标识（{host}:{pid}），排障用
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS locked_by       VARCHAR(64);
-- processed_at：成功处理时间
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS processed_at    TIMESTAMPTZ;

-- ---------- 2) 存量回填（幂等：仅处理仍为 NEW 的行）----------
UPDATE outbox_events
   SET status = CASE WHEN published THEN 'PUBLISHED' ELSE 'NEW' END
 WHERE status = 'NEW';

UPDATE outbox_events
   SET processed_at = published_at
 WHERE published = TRUE
   AND processed_at IS NULL;

-- ---------- 3) 认领 / 死信索引（部分索引，只覆盖活跃行）----------
CREATE INDEX IF NOT EXISTS idx_ob_due
    ON outbox_events (status, next_attempt_at, created_at)
 WHERE status IN ('NEW', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_ob_dead
    ON outbox_events (status, created_at)
 WHERE status = 'DEAD';

-- ---------- 4) 状态取值约束 ----------
ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS ck_ob_status;
ALTER TABLE outbox_events ADD CONSTRAINT ck_ob_status
    CHECK (status IN ('NEW', 'FAILED', 'PUBLISHED', 'DEAD'));
