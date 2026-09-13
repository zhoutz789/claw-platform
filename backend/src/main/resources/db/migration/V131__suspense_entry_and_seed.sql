-- ============================================================================
-- V131 资金路由与清分 · 差错挂账 + 种子（schema=claw）
-- 仅新建 1 张表 + 种子，绝不修改 V1–V127。
--
-- ① suspense_entry  长款/短款/未匹配/金额不符/汇兑差异的专门科目与工单，
--                   关联 reconciliation_runs.id，形成「差异 → 工单 → 处置」闭环。
-- ② 种子：CONSIGNMENT_SCAN 4 条默认分账规则 + 关键 system_config。
--
-- 表名不加 claw. 前缀（与 V87/V126/V127 一致）；全量幂等（IF NOT EXISTS / ON CONFLICT）。
-- ============================================================================

SET search_path = claw;

CREATE TABLE IF NOT EXISTS suspense_entry (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_no     VARCHAR(48) NOT NULL UNIQUE,
    scene        VARCHAR(24),
    diff_type    VARCHAR(24) NOT NULL,   -- CHANNEL_EXTRA/BOOK_EXTRA/AMOUNT_MISMATCH/FX_DIFF/UNMATCHED
    channel_ref  VARCHAR(80),            -- 通道回执/流水号
    ledger_ref   VARCHAR(64),            -- 账本 bizRef
    amount       NUMERIC(18,4) NOT NULL, -- 正=长款, 负=短款
    currency     CHAR(3) NOT NULL DEFAULT 'USD',
    recon_run_id BIGINT,                 -- 关联 reconciliation_runs.id
    status       VARCHAR(16) NOT NULL DEFAULT 'OPEN', -- OPEN/PROCESSING/RESOLVED/WRITTEN_OFF
    resolution   VARCHAR(512),
    resolved_by  BIGINT,
    resolved_at  TIMESTAMPTZ,
    tenant_id    BIGINT NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_se_status ON suspense_entry (status, created_at);

-- 种子：默认分账规则（R1 扫码购：平台服务费优先，其次站佣/物流，余额归厂家）
-- 幂等依赖 settlement_rule 的 UNIQUE (biz_scene, payee_type, rule_version)（V129）。
INSERT INTO settlement_rule (biz_scene, payee_type, basis, rate, priority, settle_cycle, status)
VALUES
  ('CONSIGNMENT_SCAN','PLATFORM',    'RATE', 0.050000,  1, 'T+0', 'ACTIVE'),
  ('CONSIGNMENT_SCAN','STATION',     'RATE', 0.100000, 10, 'T+7', 'ACTIVE'),
  ('CONSIGNMENT_SCAN','LOGISTICS',   'RATE', 0.050000, 20, 'T+7', 'ACTIVE'),
  ('CONSIGNMENT_SCAN','MANUFACTURER','RATE', 1.000000, 99, 'T+7', 'ACTIVE')
ON CONFLICT DO NOTHING;

-- 种子：制度参数（不硬编码；system_config 自带 config_key 唯一）
INSERT INTO system_config (config_key, config_value) VALUES
  ('SETTLE_CYCLE_GOODS','T+7'),        -- 货款类结算周期
  ('SETTLE_CYCLE_PLATFORM_REVENUE','T+30'),
  ('CLEARING_UNMATCHED_ALERT_HOURS','24')
ON CONFLICT (config_key) DO NOTHING;
