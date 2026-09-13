-- ============================================================================
-- V129 资金路由与清分 · L5 分账规则 + 清分指令（schema=claw）
-- 仅新建 2 张表，绝不修改 V1–V127。
--
-- ① settlement_rule    分账规则：场景/收款方/基准/优先级/生效期/结算周期，驱动统一分账引擎。
-- ② clearing_instruction 清分指令单：承载每一笔清分动作与状态机（幂等键 = instruction_no / idem_key）。
--
-- 表名不加 claw. 前缀（与 V87/V126/V127 一致）；全量幂等（IF NOT EXISTS）。
-- ============================================================================

SET search_path = claw;

-- ① 分账规则（统一分账引擎的配置源，替代散落各域的分成逻辑）
CREATE TABLE IF NOT EXISTS settlement_rule (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    biz_scene       VARCHAR(24) NOT NULL,   -- CONSIGNMENT_SCAN/RENTAL_SPLIT/TASK_SETTLE/CAPACITY/DEPOSIT/RECOVERY
    payee_type      VARCHAR(24) NOT NULL,   -- MANUFACTURER/STATION/PLATFORM/INSURANCE/LOGISTICS/OWNER
    basis           VARCHAR(16) NOT NULL,   -- RATE / FIXED / TIER
    rate            NUMERIC(9,6),           -- basis=RATE
    fixed_amount    NUMERIC(16,2),          -- basis=FIXED
    tier_json       TEXT,                   -- basis=TIER（阶梯，jsonb 结构文本）
    priority        INT         NOT NULL DEFAULT 10,  -- 越小越先扣（平台服务费通常最先）
    currency        CHAR(3)     NOT NULL DEFAULT 'USD',
    settle_cycle    VARCHAR(8)  NOT NULL DEFAULT 'T+0', -- T+0/T+1/T+7
    manufacturer_id BIGINT,                 -- 可选：按厂家细分规则
    effective_from  DATE,
    effective_to    DATE,
    rule_version    INT         NOT NULL DEFAULT 1,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (biz_scene, payee_type, rule_version)   -- 幂等播种依赖此唯一约束
);
CREATE INDEX IF NOT EXISTS idx_sr_scene ON settlement_rule (biz_scene, status, priority);

-- ② 清分指令单（幂等键 = instruction_no / idem_key）
CREATE TABLE IF NOT EXISTS clearing_instruction (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instruction_no   VARCHAR(48) NOT NULL UNIQUE,       -- 幂等键
    idem_key         VARCHAR(64) NOT NULL,              -- 业务幂等键（scene+basis_ref+payee）
    scene            VARCHAR(24) NOT NULL,              -- R1..R12
    mode             VARCHAR(16) NOT NULL,              -- AT_SOURCE/ON_ARRIVAL/BATCH
    payer_vsa_id     BIGINT REFERENCES virtual_subaccount (id),
    payee_vsa_id     BIGINT REFERENCES virtual_subaccount (id),
    payee_account_id BIGINT,                            -- 账本收款账户（ledger.accounts.id）
    amount           NUMERIC(18,4) NOT NULL,
    currency         CHAR(3)      NOT NULL DEFAULT 'USD',
    basis_ref        VARCHAR(64),                       -- 依据单号（订单/结算批次）
    ledger_biz_type  VARCHAR(32),
    ledger_biz_ref   VARCHAR(64),
    channel          VARCHAR(32),                       -- ABA_PAYWAY / BAKONG
    status           VARCHAR(16) NOT NULL DEFAULT 'CREATED', -- 见设计 §6.1
    institution_ref  VARCHAR(80),                       -- 通道回执号 —— 待通道确认
    retry_count      INT         NOT NULL DEFAULT 0,
    fail_reason      VARCHAR(512),
    sent_at          TIMESTAMPTZ,
    acked_at         TIMESTAMPTZ,
    settled_at       TIMESTAMPTZ,
    tenant_id        BIGINT      NOT NULL DEFAULT 1,
    deleted          BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (idem_key)
);
CREATE INDEX IF NOT EXISTS idx_ci_status ON clearing_instruction (status, created_at);
CREATE INDEX IF NOT EXISTS idx_ci_basis  ON clearing_instruction (basis_ref);
