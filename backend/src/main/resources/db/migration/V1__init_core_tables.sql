-- =====================================================================
-- Claw 平台 V1 基础表（S0：DB 基础表）
-- 依据：《技术开发文档 v0.4》2.2 核心表清单 + PRD v1.1 定稿
-- 范围：用户/角色包（人人经济）、资产域基础表、账户域复式记账、
--       三专户（escrow 口径，D27）、支付通道、汇率与电价快照、审计
-- 注意：S1-S5 各 Sprint 增量脚本（V2+）逐步补齐认购域/计费/合规等表
-- 通用规范：所有表带 created_at/updated_at；软删除 deleted；预留 tenant_id
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS claw;

-- ---------------------------------------------------------------------
-- 1. 用户与 KYC（domain.user / domain.role）
-- ---------------------------------------------------------------------

CREATE TABLE claw.users (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    phone           VARCHAR(32)  NOT NULL UNIQUE,
    email           VARCHAR(128),
    password_hash   VARCHAR(128),
    full_name       VARCHAR(128),
    -- KYC：PENDING 待认证 / VERIFIED 已认证（CamDigiKey）/ REJECTED
    kyc_status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    -- CamDigiKey eKYC（OAuth 2.0）返回的用户标识，S1 接入后回填
    camdigikey_ref  VARCHAR(64)  UNIQUE,
    kyc_verified_at TIMESTAMPTZ,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | FROZEN | CLOSED
    locale          VARCHAR(8)   NOT NULL DEFAULT 'en',       -- en | km | zh
    tenant_id       BIGINT       NOT NULL DEFAULT 1,
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_camdigikey ON claw.users (camdigikey_ref) WHERE camdigikey_ref IS NOT NULL;

-- 人人经济角色包：一个用户 = N 个动态角色权限包（消费者/生产者/流通者/商家/车主/司机/站方/车队管理员…）
CREATE TABLE claw.roles (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,          -- CONSUMER / PRODUCER / DISTRIBUTOR / MERCHANT / OWNER / DRIVER / STATION_OWNER / FLEET_ADMIN ...
    name_i18n   VARCHAR(64)  NOT NULL,                 -- i18n key：role.{code}.name
    grants      JSONB        NOT NULL DEFAULT '{}',    -- 权限位集合（菜单/接口/数据范围）
    auto_grant  BOOLEAN      NOT NULL DEFAULT FALSE,   -- 是否满足条件自动授予（如采购即商家）
    grant_rule  JSONB,                                 -- 授予规则引擎表达式（auto_grant=true 时使用）
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE claw.user_role_packages (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES claw.users (id),
    role_id     BIGINT      NOT NULL REFERENCES claw.roles (id),
    source      VARCHAR(16) NOT NULL DEFAULT 'APPLY',  -- APPLY 申请开通 | AUTO 规则自动 | ADMIN 人工授予
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ,
    UNIQUE (user_id, role_id)
);

-- ---------------------------------------------------------------------
-- 2. 资产域基础表（domain.asset）
-- ---------------------------------------------------------------------

CREATE TABLE claw.assets (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_type   VARCHAR(16) NOT NULL,                 -- vehicle | battery | charger | pv_station
    asset_no     VARCHAR(64) NOT NULL UNIQUE,          -- 平台唯一资产编号
    qr_code      VARCHAR(128) UNIQUE,                  -- 资产二维码（扫码收发/换电）
    owner_id     BIGINT REFERENCES claw.users (id),    -- 管理人（平台/投资者/站方）
    user_id      BIGINT REFERENCES claw.users (id),    -- 当前使用人
    status       VARCHAR(16) NOT NULL DEFAULT 'IN_STOCK',
                 -- IN_STOCK 在库 | IN_USE 使用中 | SHARED 共享中 | REPAIR 维修中 | DISABLED 停用 | SCRAPPED 报废
    tenant_id    BIGINT      NOT NULL DEFAULT 1,
    deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_assets_type_status ON claw.assets (asset_type, status);

CREATE TABLE claw.vehicles (
    asset_id     BIGINT PRIMARY KEY REFERENCES claw.assets (id),
    vin          VARCHAR(64),
    frame_no     VARCHAR(64),
    motor_no     VARCHAR(64),
    model        VARCHAR(64) NOT NULL,
    -- v0.4：融资租赁/RTO 结构（D28：与持牌租赁公司联合放款）
    contract_type VARCHAR(16),                         -- FULL_PAYMENT | RENT_TO_OWN | FLEET_LEASE
    lessor_id    BIGINT,                               -- 联合放款的持牌租赁公司（partners 表 S2 补充外键）
    protocol_ver VARCHAR(16),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE claw.batteries (
    asset_id      BIGINT PRIMARY KEY REFERENCES claw.assets (id),
    model         VARCHAR(64) NOT NULL,
    capacity_kwh  NUMERIC(8,2) NOT NULL,
    protocol_ver  VARCHAR(16),
    soh           NUMERIC(5,2) NOT NULL DEFAULT 100.00,   -- 健康度 %
    cycle_count   INT          NOT NULL DEFAULT 0,
    -- 押金 = 动态残值（FIFO 前提），由 deposit_curves（S2 V3 迁移）计算
    deposit_value NUMERIC(10,2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 状态变更历史（审计留痕，所有状态机共用）
CREATE TABLE claw.asset_status_logs (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id     BIGINT      NOT NULL REFERENCES claw.assets (id),
    from_status  VARCHAR(16),
    to_status    VARCHAR(16) NOT NULL,
    operator_id  BIGINT,
    reason       VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_asset_status_logs_asset ON claw.asset_status_logs (asset_id, created_at DESC);

-- ---------------------------------------------------------------------
-- 3. 账户域：复式记账 + 三专户（domain.ledger，S2 重点实现）
-- ---------------------------------------------------------------------

CREATE TABLE claw.accounts (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT REFERENCES claw.users (id),    -- NULL = 平台内部户
    asset_id     BIGINT REFERENCES claw.assets (id),   -- 资产子账户时关联
    account_type VARCHAR(32) NOT NULL,
                 -- MASTER 总账户 | ASSET 资产账户 | SUB 功能子账户
                 -- | DEPOSIT_LOCKED 押金冻结 | RESIDUAL_RESERVE 残值准备金专户
                 -- | BATTERY_FUND 电池基金专户 | VEHICLE_RISK 车辆风险准备金专户
    currency     CHAR(3)     NOT NULL DEFAULT 'USD',
    balance      NUMERIC(16,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    frozen       NUMERIC(16,2) NOT NULL DEFAULT 0,     -- 冻结部分（预扣/押金）
    tenant_id    BIGINT      NOT NULL DEFAULT 1,
    deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, account_type, currency, asset_id)
);

-- 复式记账流水：每笔交易 N 条分录，借贷必须平衡（应用层强制 + S2 加 DB 触发器校验）
CREATE TABLE claw.account_entries (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    txn_id        UUID        NOT NULL,                -- 同一笔交易的所有分录共享 txn_id
    account_id    BIGINT      NOT NULL REFERENCES claw.accounts (id),
    direction     CHAR(1)     NOT NULL CHECK (direction IN ('D', 'C')),  -- D 借 C 贷
    amount        NUMERIC(16,2) NOT NULL CHECK (amount > 0),
    biz_type      VARCHAR(32) NOT NULL,                -- RECHARGE 充值 | SWAP_PAY 换电结算 | DEPOSIT_HOLD 押金冻结 | FUND_ACCRUAL 基金计提 | INSTALLMENT 分期月付 ...
    biz_ref       VARCHAR(64),                         -- 业务单号（订单号/合同号），幂等键
    memo          VARCHAR(255),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_entries_txn   ON claw.account_entries (txn_id);
CREATE INDEX idx_entries_biz   ON claw.account_entries (biz_type, biz_ref);
-- 幂等：同一业务类型 + 单号 + 账户 方向唯一
CREATE UNIQUE INDEX uq_entries_idempotent ON claw.account_entries (biz_type, biz_ref, account_id, direction);

-- 三专户托管映射（D27：ABA escrow 受托口径优先）
CREATE TABLE claw.escrow_accounts (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    escrow_type           VARCHAR(32) NOT NULL,        -- RESIDUAL_RESERVE | BATTERY_FUND | VEHICLE_RISK
    custodian_bank        VARCHAR(64) NOT NULL DEFAULT 'ABA_BANK',
    bank_branch           VARCHAR(64),
    -- Q17 已确认：escrow（受托）口径优先谈判，与 ABA 确认后冻结
    custodian_legal_form  VARCHAR(16) NOT NULL DEFAULT 'ESCROW',   -- ESCROW | CUSTODIAN | TRUST
    account_ownership     VARCHAR(16) NOT NULL DEFAULT 'USER',     -- 资金所有权归用户/投资者
    bank_account_no       VARCHAR(64),
    account_id            BIGINT REFERENCES claw.accounts (id),    -- 对应平台内部账本账户
    status                VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING 待开立 | ACTIVE
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (escrow_type)
);

-- ---------------------------------------------------------------------
-- 4. 支付域基础表（domain.payment，S4 对接 ABA）
-- ---------------------------------------------------------------------

CREATE TABLE claw.payment_channels (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel     VARCHAR(32) NOT NULL UNIQUE,           -- aba_bank 托管/收付 | khqr 收单 | bakong 清算 | wing | cash
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    config      JSONB       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE claw.payment_orders (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_no         VARCHAR(64) NOT NULL UNIQUE,
    biz_ref          VARCHAR(64) NOT NULL,             -- 关联业务单（换电单/认购单/充值）
    amount_usd       NUMERIC(16,2) NOT NULL CHECK (amount_usd >= 0),
    khr_rate_snapshot NUMERIC(12,4),                   -- 下单时刻汇率快照（USD→KHR 展示保留 1 位小数）
    channel          VARCHAR(32) NOT NULL,
    payment_method   VARCHAR(16) NOT NULL DEFAULT 'KHQR',  -- KHQR | BAKONG | BANK_TRANSFER | CASH
    status           VARCHAR(16) NOT NULL DEFAULT 'CREATED',  -- CREATED | PAID | FAILED | REFUNDED
    paid_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_orders_biz ON claw.payment_orders (biz_ref);

-- ---------------------------------------------------------------------
-- 5. 汇率与电价快照（计价体系锁版：实缴实结 + 按度计价 + 时刻快照）
-- ---------------------------------------------------------------------

CREATE TABLE claw.exchange_rates (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rate_date   DATE        NOT NULL,
    usd_to_khr  NUMERIC(12,4) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rate_date)
);

-- 电价快照：EDC 牌价季度更新；换电/充电时刻锁定快照结算
CREATE TABLE claw.elec_price_snapshots (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pv_price        NUMERIC(8,4) NOT NULL,             -- 光伏供电 $0.12/度（基准）
    grid_price      NUMERIC(8,4) NOT NULL,             -- 市电 $0.18/度（EDC 牌价原价转付）
    effective_date  DATE        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (effective_date)
);

-- ---------------------------------------------------------------------
-- 6. 审计通用（所有资金敏感操作留痕）
-- ---------------------------------------------------------------------

CREATE TABLE claw.audit_logs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    BIGINT,
    action      VARCHAR(64) NOT NULL,                  -- 如 LEDGER_ENTRY_CREATE / ASSET_STATUS_CHANGE / ESCROW_CONFIG
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    detail      JSONB,
    ip          VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_target ON claw.audit_logs (target_type, target_id, created_at DESC);

-- ---------------------------------------------------------------------
-- 初始化数据：角色包种子 + 支付通道种子
-- ---------------------------------------------------------------------

INSERT INTO claw.roles (code, name_i18n, auto_grant) VALUES
    ('CONSUMER',       'role.consumer.name',       TRUE),
    ('PRODUCER',       'role.producer.name',       TRUE),
    ('DISTRIBUTOR',    'role.distributor.name',    TRUE),
    ('MERCHANT',       'role.merchant.name',       FALSE),
    ('OWNER',          'role.owner.name',          FALSE),
    ('DRIVER',         'role.driver.name',         FALSE),
    ('STATION_OWNER',  'role.station_owner.name',  FALSE),
    ('FLEET_ADMIN',    'role.fleet_admin.name',    FALSE),
    ('PLATFORM_ADMIN', 'role.platform_admin.name', FALSE);

INSERT INTO claw.payment_channels (channel) VALUES
    ('aba_bank'), ('khqr'), ('bakong'), ('wing'), ('cash');

-- 电价基准快照（锁版价：光伏 0.12 / 市电 0.18）
INSERT INTO claw.elec_price_snapshots (pv_price, grid_price, effective_date)
VALUES (0.12, 0.18, CURRENT_DATE);
