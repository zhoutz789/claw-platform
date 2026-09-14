-- ============================================================================
-- V134 资金路由与清分 · WHT 代扣台账（schema=claw）
-- ADD-ONLY：仅新建 1 张表 tax_withholding，绝不修改 V1–V133。
--
-- 背景（设计附录 B.5 / C.2，T11 WHT 代扣引擎）：
--   每笔对外付款按收款方税务档案代扣 WHT 后，落一条代扣台账，记录 gross/wht_rate/wht_amount/net、
--   申报期（tax_period = YYYY-MM）、缴纳状态，作为月度 WHT 申报底稿的数据源。
--
-- 幂等：CREATE TABLE IF NOT EXISTS；唯一索引 IF NOT EXISTS（一笔清分指令至多一条代扣记录）。
-- 表名不加 claw. 前缀（与 V87/V126–V133 一致）。
-- ============================================================================

SET search_path = claw;

CREATE TABLE IF NOT EXISTS tax_withholding (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    clearing_instruction_id BIGINT REFERENCES clearing_instruction (id),
    payee_vsa_id           BIGINT REFERENCES virtual_subaccount (id),
    gross_amount           NUMERIC(18,4) NOT NULL,
    wht_rate               NUMERIC(9,6)  NOT NULL DEFAULT 0,
    wht_amount             NUMERIC(18,4) NOT NULL DEFAULT 0,
    net_amount             NUMERIC(18,4) NOT NULL,
    currency               CHAR(3)       NOT NULL DEFAULT 'USD',
    tax_period             VARCHAR(7)    NOT NULL,   -- YYYY-MM 申报期
    paid_status            VARCHAR(16)   NOT NULL DEFAULT 'UNPAID',
    paid_at                TIMESTAMPTZ,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 一笔清分指令至多一条代扣记录（WHT=0 时不写本表，故不触发重复）
CREATE UNIQUE INDEX IF NOT EXISTS uq_tw_instruction ON tax_withholding (clearing_instruction_id);

-- 申报期 + 缴纳状态勾对（月度申报底稿按 (tax_period, paid_status) 拉取）
CREATE INDEX IF NOT EXISTS idx_tw_period ON tax_withholding (tax_period, paid_status);
