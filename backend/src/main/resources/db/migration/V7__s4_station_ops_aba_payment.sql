-- =====================================================================
-- Claw 平台 V7 增量表（S4：服务站 APP + ABA 托管对接）
-- 依据：《技术开发文档 v0.4》2.2 支付与托管 + 4.1 ABA 银行托管对接 + API 清单
--   · 服务站：扫码收发（POST /station/scan-in、/scan-out）、电池位（GET /station/slots）、
--             日账单（GET /station/daily-bill）
--   · 支付域：三专户 KHQR 收单 + Bakong 清算（零牌照运营）、充值/提现（ABA）、
--             T+1 日终对账（ledger_entries 与 ABA 流水双向对账）
-- 范围：
--   · station_handover_orders 服务站扫码收发单（发放 OUT / 回收 IN）
--   · payment_orders 增列 escrow_type（三专户收单目标）
--   · wallet_txns 充值/提现单（状态机）
--   · reconciliation_runs 日终对账批次（差异留痕）
--   · 种子：ABA 通道 config + escrow_accounts 模拟银行账号
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 服务站扫码收发单（domain.station）
--    站方扫码发放满电电池（OUT）/ 回收欠电电池（IN），与换电单可关联可独立
-- ---------------------------------------------------------------------
CREATE TABLE claw.station_handover_orders (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    handover_no  VARCHAR(64) NOT NULL UNIQUE,
    station_id   BIGINT      NOT NULL REFERENCES claw.stations (id),
    battery_id   BIGINT      NOT NULL REFERENCES claw.assets (id),
    op_type      VARCHAR(8)  NOT NULL,                -- OUT 发放 | IN 回收
    order_no     VARCHAR(64),                         -- 关联换电单（可空，独立收发不关联）
    operator_id  BIGINT      REFERENCES claw.users (id),
    soc          NUMERIC(5,2),                        -- 回收时 BMS 上报电量 %
    status       VARCHAR(16) NOT NULL DEFAULT 'DONE',
    memo         VARCHAR(255),
    tenant_id    BIGINT      NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_handover_station ON claw.station_handover_orders (station_id, created_at DESC);
CREATE INDEX idx_handover_battery  ON claw.station_handover_orders (battery_id, created_at DESC);

-- ---------------------------------------------------------------------
-- 2. payment_orders 增列 escrow_type：三专户收单目标
--    NULL = 普通充值/换电支付；RESIDUAL_RESERVE/BATTERY_FUND/VEHICLE_RISK = 定向收单
-- ---------------------------------------------------------------------
ALTER TABLE claw.payment_orders ADD COLUMN escrow_type VARCHAR(32);

-- ---------------------------------------------------------------------
-- 3. 钱包交易单（充值/提现，domain.payment）
--    状态机：
--      充值 RECHARGE：CREATED →（ABA 回调对账后）→ PAID
--      提现 WITHDRAW：CREATED → PROCESSING（账本已扣减）→ SUCCESS / FAILED_REFUND（失败退回账本）
-- ---------------------------------------------------------------------
CREATE TABLE claw.wallet_txns (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    txn_no           VARCHAR(64) NOT NULL UNIQUE,
    user_id          BIGINT      NOT NULL REFERENCES claw.users (id),
    txn_type         VARCHAR(16) NOT NULL,            -- RECHARGE | WITHDRAW
    amount_usd       NUMERIC(16,2) NOT NULL CHECK (amount_usd > 0),
    fee_usd          NUMERIC(16,2) NOT NULL DEFAULT 0,
    channel          VARCHAR(32) NOT NULL DEFAULT 'khqr',  -- khqr | bakong | bank_transfer
    payment_order_no VARCHAR(64),                     -- 关联支付单（KHQR 收单）
    aba_ref          VARCHAR(64),                     -- ABA 侧流水号（出金/入金回执）
    bank_account_json JSONB,                          -- 提现收款账户信息
    status           VARCHAR(16) NOT NULL DEFAULT 'CREATED',
                     -- CREATED | PAID | PROCESSING | SUCCESS | FAILED_REFUND
    fail_reason      VARCHAR(255),
    tenant_id        BIGINT      NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallet_txns_user ON claw.wallet_txns (user_id, created_at DESC);
CREATE INDEX idx_wallet_txns_type ON claw.wallet_txns (txn_type, status, created_at DESC);

-- ---------------------------------------------------------------------
-- 4. 日终对账批次（domain.payment，技术文档 4.1：每日 T+1 双向对账）
--    platform_total：平台账本当日资金净额；bank_total：ABA 流水当日资金净额
--    差异 mismatch_count>0 时告警人工处理
-- ---------------------------------------------------------------------
CREATE TABLE claw.reconciliation_runs (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_date       DATE        NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'RUNNING',  -- RUNNING | MATCHED | MISMATCH
    platform_total NUMERIC(16,2) NOT NULL DEFAULT 0,        -- 平台账本净额（充值为正、提现为负）
    bank_total     NUMERIC(16,2) NOT NULL DEFAULT 0,        -- ABA 流水净额
    matched_count  INT         NOT NULL DEFAULT 0,
    mismatch_count INT         NOT NULL DEFAULT 0,
    detail_json    JSONB,                                   -- 差异明细（单据号/金额/方向）
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_date)
);

-- ---------------------------------------------------------------------
-- 5. 种子：ABA 通道 config + escrow_accounts 模拟银行账号（Q17 escrow 受托口径）
-- ---------------------------------------------------------------------
UPDATE claw.payment_channels
   SET config = '{"bank":"ABA_BANK","khqr":"enabled","bakong":"enabled","payout":"enabled"}'
 WHERE channel IN ('khqr', 'bakong', 'aba_bank');

UPDATE claw.escrow_accounts SET bank_account_no = 'ABA-ESCROW-0001', status = 'ACTIVE'
 WHERE escrow_type = 'RESIDUAL_RESERVE';
UPDATE claw.escrow_accounts SET bank_account_no = 'ABA-ESCROW-0002', status = 'ACTIVE'
 WHERE escrow_type = 'BATTERY_FUND';
UPDATE claw.escrow_accounts SET bank_account_no = 'ABA-ESCROW-0003', status = 'ACTIVE'
 WHERE escrow_type = 'VEHICLE_RISK';
