-- =====================================================================
-- Claw 平台 V71 增量表（v3 — 共享池容量预订 / 运营回佣）
-- 依据：《共享池容量预订-操作路径与自动分成设计.md》（周老板 2026-09-06 拍板）
-- 范围：
--   · capacity_plans          容量预订计划（厂家发布，拆 N 个容量单位）
--   · capacity_subscriptions  定购（用户认购容量单位，预付产能款直付厂家托管）
--   · capacity_rebate_rules   回佣规则（从厂家 owner_share 计提比例）
--   · capacity_rebate_settlements  回佣明细（按定购单位二次拆分落账）
-- 设计要点：
--   · 资产产权仍为厂家单一主体（寄售 CONSIGNED），本域只处理"使用产能/回佣权"，
--     不触碰 asset_ownership 单主约束，不构成资产按份共有。
--   · 资金不过平台：定购预付款直付厂家托管（ledger 分录，平台不持资金池）。
--   · 回佣来自厂家 owner_share 计提，与真实运营绩效挂钩、不保底不保息。
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 容量预订计划（capacity_plans）
--    厂家把一台自有(寄售)资产的可使用产能拆成 total_units 个容量单位对外预订。
-- ---------------------------------------------------------------------
CREATE TABLE claw.capacity_plans (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),
    pool_entry_id   BIGINT REFERENCES claw.shared_pool_entries (id),

    -- 计划发布方（厂家/资产所有人）
    owner_user_id   BIGINT NOT NULL REFERENCES claw.users (id),

    -- 产能拆分
    total_units     INTEGER NOT NULL,                      -- 总容量单位数（如 30）
    subscribed_units INTEGER NOT NULL DEFAULT 0,           -- 已定购单位数（进度展示，下单时递增）
    unit_price      NUMERIC(18,4) NOT NULL,                -- 每单位产能预付款

    -- 容量类型（P0-2）：SERIAL=优先权+回佣资格(车辆等串行) / PARALLEL=真实可并行使用额度(无人机/充电桩)
    capacity_type   VARCHAR(16) NOT NULL DEFAULT 'SERIAL',

    -- 回佣率：从厂家 owner_share 中计提给定购单位的比例（0~1）
    rebate_rate     NUMERIC(6,4) NOT NULL DEFAULT 0.10,

    -- 预订窗口
    window_start    TIMESTAMPTZ,
    window_end      TIMESTAMPTZ,

    -- 计划状态
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN',  -- DRAFT | OPEN | CLOSED | CANCELLED

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cap_plan_asset   ON claw.capacity_plans (asset_id)             WHERE deleted = FALSE;
CREATE INDEX idx_cap_plan_owner   ON claw.capacity_plans (owner_user_id)        WHERE deleted = FALSE;
CREATE INDEX idx_cap_plan_status  ON claw.capacity_plans (status)              WHERE deleted = FALSE AND status = 'OPEN';

-- ---------------------------------------------------------------------
-- 2. 容量定购（capacity_subscriptions）
--    用户认购 N 个容量单位，预付产能款（直付厂家托管）。
-- ---------------------------------------------------------------------
CREATE TABLE claw.capacity_subscriptions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id         BIGINT NOT NULL REFERENCES claw.capacity_plans (id),
    subscriber_user_id BIGINT NOT NULL REFERENCES claw.users (id),

    unit_count      INTEGER NOT NULL,                      -- 认购单位数
    prepaid_amount  NUMERIC(18,4) NOT NULL,                -- 预付产能款 = unit_count * unit_price

    -- 定购状态（P1：转让/退出机制）
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- PENDING | ACTIVE | REFUNDED | CANCELLED

    -- 关联预付 ledger 单据（幂等/对账）
    ledger_txn_id   VARCHAR(64),

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cap_sub_plan ON claw.capacity_subscriptions (plan_id)              WHERE deleted = FALSE;
CREATE INDEX idx_cap_sub_user ON claw.capacity_subscriptions (subscriber_user_id)   WHERE deleted = FALSE;
CREATE UNIQUE INDEX uq_cap_sub_active
    ON claw.capacity_subscriptions (plan_id, subscriber_user_id) WHERE deleted = FALSE AND status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- 3. 回佣规则（capacity_rebate_rules）
--    计划级回佣配置；支持未来多档扩展，现单档。
-- ---------------------------------------------------------------------
CREATE TABLE claw.capacity_rebate_rules (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id         BIGINT NOT NULL REFERENCES claw.capacity_plans (id),

    rebate_rate     NUMERIC(6,4) NOT NULL DEFAULT 0.10,    -- 与计划一致，结算时二次校验
    min_payout      NUMERIC(18,4) NOT NULL DEFAULT 0.01,   -- 单笔回佣下限（防零头噪声）

    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | SUPERSEDED

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cap_rebate_plan ON claw.capacity_rebate_rules (plan_id) WHERE deleted = FALSE AND status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- 4. 回佣明细（capacity_rebate_settlements）
--    每笔租赁结算的厂家 owner_share，按 unit_count/total_units 拆分到各定购单位。
-- ---------------------------------------------------------------------
CREATE TABLE claw.capacity_rebate_settlements (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_no   VARCHAR(64) NOT NULL UNIQUE,

    -- 来源
    rental_order_id BIGINT REFERENCES claw.rental_orders (id),
    plan_id         BIGINT NOT NULL REFERENCES claw.capacity_plans (id),
    pool_entry_id   BIGINT REFERENCES claw.shared_pool_entries (id),

    -- 计提基准
    owner_share_base NUMERIC(18,4) NOT NULL,              -- 该笔租赁的厂家所得
    rebate_total     NUMERIC(18,4) NOT NULL,              -- 计提回佣总额 = owner_share_base * rebate_rate

    -- 本行对应定购单位
    subscriber_user_id BIGINT NOT NULL REFERENCES claw.users (id),
    unit_count      INTEGER NOT NULL,
    ratio           NUMERIC(10,6) NOT NULL,               -- unit_count / total_units
    amount          NUMERIC(18,4) NOT NULL,              -- 该定购单位实得 = rebate_total * ratio

    ledger_txn_id   VARCHAR(64),                          -- 复式记账 txnId
    status          VARCHAR(16) NOT NULL DEFAULT 'SETTLED', -- PENDING | SETTLED | FAILED

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cap_rebate_settlement_plan ON claw.capacity_rebate_settlements (plan_id) WHERE deleted = FALSE;
CREATE INDEX idx_cap_rebate_settlement_sub  ON claw.capacity_rebate_settlements (subscriber_user_id) WHERE deleted = FALSE;
