-- =====================================================================
-- Claw 平台 V10 增量表（v2.0 — 站方资金实体 + 个人站风控）
-- 依据：《PRD v2.0》4.13-4.15 + D43/D44 + 《全风险规避方案 v2.0》R1-R3
-- 范围：
--   · operator_accounts: 站方资金账户（管理收益/服务费/光伏收益/回收代办）
--   · operator_bonds: 站长保证金（R3 动态核定 = 基础押金 + 经手资产价值×5%）
--   · operator_kyc_records: 站长 KYC 记录（R1 准入门槛 + CamDigiKey eKYC）
--   · operator_risk_events: 站方风控事件（R1 实时监控 + 自动熔断）
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 站方资金账户（operator_accounts）
--    v2.0 新增：站方作为资金交易对手方的核心实体
--    对应 T-A7 修复：站方需建模为资金实体
--    对应 PRD 4.13：管理收益/服务费/光伏收益/残值回收代办
-- ---------------------------------------------------------------------
CREATE TABLE claw.operator_accounts (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id     BIGINT NOT NULL REFERENCES claw.users (id),  -- 站长 user_id
    station_id      BIGINT REFERENCES claw.stations (id),       -- 关联站点（个人站可空，后补）
    account_type    VARCHAR(32) NOT NULL,  -- MANAGEMENT_FEE | SERVICE_FEE | PV_REVENUE | RECOVERY | BOND
    balance         NUMERIC(18,4) NOT NULL DEFAULT 0,
    frozen          NUMERIC(18,4) NOT NULL DEFAULT 0,
    currency        VARCHAR(8)  NOT NULL DEFAULT 'USD',
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | FROZEN | CLOSED
    opened_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_operator_accounts_uniq
    ON claw.operator_accounts (operator_id, station_id, account_type, currency)
    WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 2. 站长保证金（operator_bonds）
--    R3 动态核定：base_bond + managed_asset_value * bond_rate
--    对应 PRD D43：个人站分级管理
--    对应 R3：站长押金动态核定（资产越多押金越高）
-- ---------------------------------------------------------------------
CREATE TABLE claw.operator_bonds (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id           BIGINT NOT NULL REFERENCES claw.users (id),
    station_id            BIGINT REFERENCES claw.stations (id),

    -- 站长类型（R1 分级管理）
    operator_type         VARCHAR(16) NOT NULL,  -- ENTERPRISE | INDIVIDUAL | PARTNER

    -- 保证金计算（R3 动态核定）
    base_bond             NUMERIC(18,4) NOT NULL,  -- 基础押金（企业$2000/个人$500/合作$1000）
    managed_asset_value   NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 经手资产总价值
    bond_rate             NUMERIC(6,4)  NOT NULL DEFAULT 0.05,  -- 5% 经手资产价值

    -- 实际保证金要求
    required_bond         NUMERIC(18,4) NOT NULL,  -- = base_bond + managed_asset_value * bond_rate
    posted_bond           NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 已缴纳保证金
    shortfall             NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 缺口 = required - posted

    -- 状态
    status                VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING | SUFFICIENT | SHORTFALL | FROZEN
    reviewed_by           BIGINT REFERENCES claw.users (id),
    reviewed_at           TIMESTAMPTZ,

    tenant_id             BIGINT      NOT NULL DEFAULT 1,
    deleted               BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_operator_bonds_operator ON claw.operator_bonds (operator_id) WHERE deleted = FALSE;
CREATE INDEX idx_operator_bonds_station  ON claw.operator_bonds (station_id)  WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 3. 站长 KYC 记录（operator_kyc_records）
--    R1 准入门槛：CamDigiKey eKYC + 信用分 + 无犯罪记录
--    个人站须通过完整 KYC 才能上线
-- ---------------------------------------------------------------------
CREATE TABLE claw.operator_kyc_records (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id     BIGINT NOT NULL REFERENCES claw.users (id),
    station_id      BIGINT REFERENCES claw.stations (id),

    -- KYC 方法
    kyc_method      VARCHAR(16) NOT NULL,  -- CAMDIGIKEY | MANUAL | BANK_VERIFY

    -- CamDigiKey eKYC 返回
    kyc_transaction_id  VARCHAR(128),     -- CamDigiKey 交易号
    full_name            VARCHAR(128),
    id_number            VARCHAR(64),     -- 国民身份证号（加密存储）
    id_type              VARCHAR(16),     -- NATIONAL_ID | PASSPORT
    date_of_birth        DATE,
    address              TEXT,
    phone_verified       BOOLEAN,

    -- 信用评估
    claw_score           INTEGER,         -- Claw Score 信用分（0-1000）
    background_check      VARCHAR(16),    -- PASS | FAIL | PENDING
    criminal_record       BOOLEAN,        -- true = 有犯罪记录

    -- 审批
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | REJECTED | EXPIRED
    approved_by         BIGINT REFERENCES claw.users (id),
    approved_at         TIMESTAMPTZ,
    reject_reason       TEXT,

    tenant_id           BIGINT      NOT NULL DEFAULT 1,
    deleted             BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_operator_kyc_operator ON claw.operator_kyc_records (operator_id) WHERE deleted = FALSE;
CREATE INDEX idx_operator_kyc_status   ON claw.operator_kyc_records (status)       WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 4. 站方风控事件（operator_risk_events）
--    R1 实时异常监控 + 自动熔断
--    对应 PRD 4.15：资金不过站 + 异常自动冻结
-- ---------------------------------------------------------------------
CREATE TABLE claw.operator_risk_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id     BIGINT NOT NULL REFERENCES claw.users (id),
    station_id      BIGINT REFERENCES claw.stations (id),

    -- 事件类型
    event_type      VARCHAR(32) NOT NULL,  -- BOND_SHORTFALL | ASSET_MISSING | RECONCILIATION_FAIL
                                           -- | COMPLAINT_SPIKE | UNUSUAL_TRANSACTION | FRAUD_SUSPECTED
    severity        VARCHAR(8)  NOT NULL,  -- LOW | MEDIUM | HIGH | CRITICAL

    -- 事件详情
    description     TEXT NOT NULL,
    detected_value  NUMERIC(18,4),         -- 涉及金额（如缺口/异常交易额）
    expected_value  NUMERIC(18,4),         -- 预期金额
    metadata        JSONB,                  -- 附加数据

    -- 自动熔断动作
    auto_action     VARCHAR(32),           -- NONE | ALERT_ONLY | FREEZE_ACCOUNT | SUSPEND_OPERATOR
    action_taken    BOOLEAN NOT NULL DEFAULT FALSE,
    action_at       TIMESTAMPTZ,

    -- 人工复核
    resolved        BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_by     BIGINT REFERENCES claw.users (id),
    resolved_at     TIMESTAMPTZ,
    resolution_note TEXT,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_operator_risk_operator ON claw.operator_risk_events (operator_id) WHERE deleted = FALSE;
CREATE INDEX idx_operator_risk_severity ON claw.operator_risk_events (severity)    WHERE deleted = FALSE;
CREATE INDEX idx_operator_risk_unresolved ON claw.operator_risk_events (resolved)  WHERE deleted = FALSE AND resolved = FALSE;

-- ---------------------------------------------------------------------
-- 5. 种子数据：站方资金账户类型
--    说明：operator_id=0 为"系统债券账户"占位，需先保证 users 中存在 id=0 的系统用户，
--          否则外键 operator_accounts_operator_id_fkey 违约。此处补一个系统用户种子。
-- ---------------------------------------------------------------------
INSERT INTO claw.users (id, phone, full_name, status, tenant_id)
OVERRIDING SYSTEM VALUE
VALUES (0, 'system@claw.local', 'System', 'ACTIVE', 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO claw.operator_accounts (operator_id, station_id, account_type, balance, status)
SELECT 0, NULL, 'BOND', 0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM claw.operator_accounts WHERE account_type = 'BOND' AND operator_id = 0);

-- ---------------------------------------------------------------------
-- 6. 触发器：operator_bonds 自动计算 required_bond + shortfall
--    当 managed_asset_value 或 bond_rate 变更时自动重算
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_calc_operator_bond()
RETURNS TRIGGER AS $$
BEGIN
    NEW.required_bond := NEW.base_bond + (NEW.managed_asset_value * NEW.bond_rate);
    NEW.shortfall := GREATEST(NEW.required_bond - NEW.posted_bond, 0);

    -- 自动状态更新
    IF NEW.shortfall = 0 AND NEW.posted_bond >= NEW.required_bond THEN
        NEW.status := 'SUFFICIENT';
    ELSIF NEW.shortfall > 0 THEN
        NEW.status := 'SHORTFALL';
    END IF;

    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_operator_bond_calc
    BEFORE INSERT OR UPDATE ON claw.operator_bonds
    FOR EACH ROW EXECUTE FUNCTION claw.fn_calc_operator_bond();

-- ---------------------------------------------------------------------
-- 7. 触发器：风控事件自动熔断
--    severity=CRITICAL 时自动 FREEZE_ACCOUNT
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_auto_circuit_breaker()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.severity = 'CRITICAL' AND NEW.auto_action IS NULL THEN
        NEW.auto_action := 'SUSPEND_OPERATOR';
    ELSIF NEW.severity = 'HIGH' AND NEW.auto_action IS NULL THEN
        NEW.auto_action := 'FREEZE_ACCOUNT';
    ELSIF NEW.severity = 'MEDIUM' AND NEW.auto_action IS NULL THEN
        NEW.auto_action := 'ALERT_ONLY';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_operator_risk_auto_action
    BEFORE INSERT ON claw.operator_risk_events
    FOR EACH ROW EXECUTE FUNCTION claw.fn_auto_circuit_breaker();
