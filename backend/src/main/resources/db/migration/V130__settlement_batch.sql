-- ============================================================================
-- V130 资金路由与清分 · 结算批次（schema=claw）
-- 仅新建 2 张表，绝不修改 V1–V127。
--
-- 用途：周期汇总 → 审核 → 下发 → 回执 → 对账闭环；due_date 承载 T+N（货款 T+7）。
-- ① settlement_batch       结算批次
-- ② settlement_batch_item  批次明细（关联 clearing_instruction / virtual_subaccount）
--
-- 表名不加 claw. 前缀（与 V87/V126/V127 一致）；全量幂等（IF NOT EXISTS）。
-- ============================================================================

SET search_path = claw;

CREATE TABLE IF NOT EXISTS settlement_batch (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_no     VARCHAR(48) NOT NULL UNIQUE,
    biz_scene    VARCHAR(24) NOT NULL,
    cycle        VARCHAR(8)  NOT NULL,          -- T+1 / T+7
    currency     CHAR(3)     NOT NULL DEFAULT 'USD',
    period_start TIMESTAMPTZ NOT NULL,
    period_end   TIMESTAMPTZ NOT NULL,
    due_date     DATE,                          -- 应付日 = period_end + N（货款类 T+7）
    total_amount NUMERIC(18,4) NOT NULL DEFAULT 0,
    item_count   INT           NOT NULL DEFAULT 0,
    status       VARCHAR(16)   NOT NULL DEFAULT 'COLLECTING', -- 见设计 §6.2
    approved_by  BIGINT,
    approved_at  TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ,
    settled_at   TIMESTAMPTZ,
    fail_reason  VARCHAR(512),
    tenant_id    BIGINT NOT NULL DEFAULT 1,
    deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sb_status ON settlement_batch (status, due_date);

CREATE TABLE IF NOT EXISTS settlement_batch_item (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id                BIGINT NOT NULL REFERENCES settlement_batch (id) ON DELETE CASCADE,
    clearing_instruction_id BIGINT REFERENCES clearing_instruction (id),
    payee_vsa_id            BIGINT REFERENCES virtual_subaccount (id),
    payee_account_id        BIGINT,
    amount                  NUMERIC(18,4) NOT NULL,
    currency                CHAR(3) NOT NULL DEFAULT 'USD',
    status                  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sbi_batch ON settlement_batch_item (batch_id);
