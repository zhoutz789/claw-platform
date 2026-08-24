-- =====================================================================
-- Claw 平台 V12 增量表（v2.0 — 共享车辆/电池池 6 张表）
-- 依据：《PRD v2.0》4.16 + D39/D40 + 《全风险规避方案 v2.0》修改4/5
-- 范围：
--   · asset_ownership: 资产产权记录（全款购买 → 持有 → 入池/回收/以旧换新）
--   · shared_pool_entries: 共享池入池记录（资产在哪个站、状态、分成比例）
--   · rental_orders: 租赁订单（换电租赁/车辆租赁统一抽象）
--   · rental_usage_sessions: 使用明细（按量计费拆分：基础费+使用费+占用费）
--   · revenue_split_rules: 分成规则（owner≥50% / station≥15% / platform=10% / insurance=5%）
--   · revenue_settlements: 分账结算记录
-- 对应修改4：个人可开站灵活换电 → 共享池运营
-- 对应修改5：资产不设寿命 → 产权追踪到回收全生命周期
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 资产产权记录（asset_ownership）
--    全款购买 = 用户全款购买资产后的产权凭证
--    修改5：资产使用寿命不设限，产权永久归所有人
--    产权链与 custody_transfers（V14）配合追踪管理权转移
-- ---------------------------------------------------------------------
CREATE TABLE claw.asset_ownership (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),
    owner_user_id   BIGINT NOT NULL REFERENCES claw.users (id),

    -- 产权类型
    ownership_type  VARCHAR(16) NOT NULL,  -- SELF_USE | SHARED
    -- SELF_USE: 自用（不入池）
    -- SHARED: 入共享池（其他用户可租用，产生分成收益）

    -- 购买信息
    purchase_price  NUMERIC(18,4) NOT NULL,
    purchase_date   DATE NOT NULL,
    purchase_order_no VARCHAR(64),         -- 关联购买订单号

    -- 产权状态
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | RECOVERED | TRADED_IN | SOLD
    -- ACTIVE: 持有中
    -- RECOVERED: 已残值回收（V13 recovery_orders 关联）
    -- TRADED_IN: 已以旧换新（关联新资产）
    -- SOLD: 已转售

    -- 回收/换新关联
    recovery_order_id BIGINT,              -- 关联 V13 recovery_orders.id
    new_asset_id      BIGINT REFERENCES claw.assets (id),  -- 以旧换新后的新资产

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_asset_ownership_active
    ON claw.asset_ownership (asset_id) WHERE deleted = FALSE AND status = 'ACTIVE';
CREATE INDEX idx_ownership_owner ON claw.asset_ownership (owner_user_id) WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 2. 共享池入池记录（shared_pool_entries）
--    资产所有人将资产放入共享池，指定投放站点
--    对应 PRD 4.16：共享池运营
--    D40：灵活换电 → 任何站点均可接受共享池资产
-- ---------------------------------------------------------------------
CREATE TABLE claw.shared_pool_entries (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id            BIGINT NOT NULL REFERENCES claw.assets (id),
    owner_user_id       BIGINT NOT NULL REFERENCES claw.users (id),
    ownership_id        BIGINT NOT NULL REFERENCES claw.asset_ownership (id),

    -- 投放站点
    current_station_id  BIGINT REFERENCES claw.stations (id),

    -- 入池状态
    status              VARCHAR(16) NOT NULL DEFAULT 'IN_POOL',
    -- IN_POOL: 在池待租
    -- IN_USE: 被租用中
    -- IN_TRANSIT: 站间调拨中
    -- REMOVED: 已出池（所有人取回或回收）

    -- 分成比例（所有人自定义，但须满足下限约束）
    owner_split_rate    NUMERIC(6,4) NOT NULL,   -- 所有人分成比例（≥50%）
    station_split_rate  NUMERIC(6,4) NOT NULL DEFAULT 0.15,  -- 站点分成比例（≥15%）
    -- platform_split_rate = 10%, insurance_split_rate = 5%（固定，由 revenue_split_rules 约束）

    -- 使用计费参数
    daily_usage_fee     NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 日租金（车辆）
    per_swap_fee       NUMERIC(18,4) NOT NULL DEFAULT 0,   -- 单次换电费（电池）
    self_charge_free_window_minutes INTEGER NOT NULL DEFAULT 120,  -- 自充电免占用费窗口（分钟）

    -- 时间戳
    pooled_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at          TIMESTAMPTZ,

    tenant_id           BIGINT      NOT NULL DEFAULT 1,
    deleted             BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pool_asset   ON claw.shared_pool_entries (asset_id)            WHERE deleted = FALSE;
CREATE INDEX idx_pool_owner   ON claw.shared_pool_entries (owner_user_id)        WHERE deleted = FALSE;
CREATE INDEX idx_pool_station ON claw.shared_pool_entries (current_station_id)   WHERE deleted = FALSE AND status = 'IN_POOL';
CREATE INDEX idx_pool_status  ON claw.shared_pool_entries (status)               WHERE deleted = FALSE;

-- CHECK 约束：所有人分成 ≥ 50%，站点分成 ≥ 15%
ALTER TABLE claw.shared_pool_entries ADD CONSTRAINT chk_owner_split_min
    CHECK (owner_split_rate >= 0.50);
ALTER TABLE claw.shared_pool_entries ADD CONSTRAINT chk_station_split_min
    CHECK (station_split_rate >= 0.15);

-- ---------------------------------------------------------------------
-- 3. 租赁订单（rental_orders）
--    统一抽象：换电租赁（电池）和车辆租赁
--    对应 PRD 4.16：用户可租用共享池中的资产
-- ---------------------------------------------------------------------
CREATE TABLE claw.rental_orders (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_no        VARCHAR(64) NOT NULL UNIQUE,

    -- 资产与参与方
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),
    pool_entry_id   BIGINT REFERENCES claw.shared_pool_entries (id),
    renter_user_id  BIGINT NOT NULL REFERENCES claw.users (id),
    station_id      BIGINT REFERENCES claw.stations (id),

    -- 租赁类型
    rental_type     VARCHAR(20) NOT NULL,  -- BATTERY_EXCHANGE | VEHICLE_RENTAL

    -- 关联换电订单（如适用）
    swap_order_id   BIGINT,

    -- 订单状态
    status          VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    -- CREATED | ACTIVE | COMPLETED | CANCELLED | DISPUTED

    -- 金额
    total_fee       NUMERIC(18,4) NOT NULL DEFAULT 0,
    owner_share     NUMERIC(18,4) NOT NULL DEFAULT 0,
    station_share   NUMERIC(18,4) NOT NULL DEFAULT 0,
    platform_share  NUMERIC(18,4) NOT NULL DEFAULT 0,
    insurance_share NUMERIC(18,4) NOT NULL DEFAULT 0,

    -- 时间
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rental_renter  ON claw.rental_orders (renter_user_id) WHERE deleted = FALSE;
CREATE INDEX idx_rental_station ON claw.rental_orders (station_id)    WHERE deleted = FALSE;
CREATE INDEX idx_rental_asset   ON claw.rental_orders (asset_id)      WHERE deleted = FALSE;
CREATE INDEX idx_rental_status  ON claw.rental_orders (status)        WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 4. 使用明细（rental_usage_sessions）
--    按量计费拆分：基础费 + 使用费 + 占用费
--    对应 PRD 4.16：灵活计费（自充电免占用费窗口）
-- ---------------------------------------------------------------------
CREATE TABLE claw.rental_usage_sessions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rental_order_id BIGINT NOT NULL REFERENCES claw.rental_orders (id),

    -- 使用量
    usage_kwh       NUMERIC(10,4),         -- 用电量（kWh）
    usage_hours     NUMERIC(10,2),         -- 使用时长（小时）

    -- 费用拆分
    base_fee        NUMERIC(18,4) NOT NULL DEFAULT 0,   -- 基础费（起步价）
    usage_fee       NUMERIC(18,4) NOT NULL DEFAULT 0,   -- 使用费（按量计费）
    occupancy_fee   NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 占用费（超时占用）
    total_fee       NUMERIC(18,4) NOT NULL DEFAULT 0,  -- = base + usage + occupancy

    -- 自充电标记（自充电在免占用费窗口内免占用费）
    is_self_charge  BOOLEAN NOT NULL DEFAULT FALSE,
    self_charge_minutes INTEGER,              -- 自充电时长（分钟）

    -- 时间
    session_start   TIMESTAMPTZ NOT NULL,
    session_end     TIMESTAMPTZ,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_rental ON claw.rental_usage_sessions (rental_order_id) WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 5. 分成规则（revenue_split_rules）
--    对应修改5：分成下限 50%（所有人 ≥50%）
--    平台 10% + 保险 5% 为固定值
--    所有人可在 50%-80% 范围自定义，站点 15%-25%
-- ---------------------------------------------------------------------
CREATE TABLE claw.revenue_split_rules (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),
    pool_entry_id   BIGINT REFERENCES claw.shared_pool_entries (id),

    -- 分成比例（百分比）
    owner_rate      NUMERIC(6,4) NOT NULL,        -- 所有人分成（≥50%）
    station_rate    NUMERIC(6,4) NOT NULL DEFAULT 0.15,  -- 站点分成（≥15%）
    platform_rate   NUMERIC(6,4) NOT NULL DEFAULT 0.10,  -- 平台分成（固定10%）
    insurance_rate  NUMERIC(6,4) NOT NULL DEFAULT 0.05,  -- 保险分成（固定5%）

    -- 计费基准
    share_basis     VARCHAR(16) NOT NULL DEFAULT 'PER_SWAP',  -- PER_SWAP | PER_DAY | PER_KWH

    -- 生效范围
    effective_from  DATE NOT NULL,
    effective_to    DATE,

    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | EXPIRED | SUPERSEDED

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- CHECK 约束：分成总和 = 100%
ALTER TABLE claw.revenue_split_rules ADD CONSTRAINT chk_split_total
    CHECK (owner_rate + station_rate + platform_rate + insurance_rate = 1.0);
-- 所有人分成 ≥ 50%
ALTER TABLE claw.revenue_split_rules ADD CONSTRAINT chk_owner_min
    CHECK (owner_rate >= 0.50);
-- 站点分成 ≥ 15%
ALTER TABLE claw.revenue_split_rules ADD CONSTRAINT chk_station_min
    CHECK (station_rate >= 0.15);
-- 平台分成 = 10%
ALTER TABLE claw.revenue_split_rules ADD CONSTRAINT chk_platform_fixed
    CHECK (platform_rate = 0.10);
-- 保险分成 = 5%
ALTER TABLE claw.revenue_split_rules ADD CONSTRAINT chk_insurance_fixed
    CHECK (insurance_rate = 0.05);

CREATE INDEX idx_split_asset ON claw.revenue_split_rules (asset_id) WHERE deleted = FALSE AND status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- 6. 分账结算记录（revenue_settlements）
--    按日/周/月结算：总收益拆分到各方账户
--    对应 PRD 4.16：分账到账
-- ---------------------------------------------------------------------
CREATE TABLE claw.revenue_settlements (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_no   VARCHAR(64) NOT NULL UNIQUE,
    settlement_date DATE NOT NULL,

    -- 结算范围
    station_id      BIGINT REFERENCES claw.stations (id),
    pool_entry_id   BIGINT REFERENCES claw.shared_pool_entries (id),

    -- 总收益
    total_revenue   NUMERIC(18,4) NOT NULL,

    -- 分账明细
    owner_share     NUMERIC(18,4) NOT NULL,
    station_share   NUMERIC(18,4) NOT NULL,
    platform_share  NUMERIC(18,4) NOT NULL,
    insurance_share NUMERIC(18,4) NOT NULL,

    -- 关联账本
    ledger_txn_id   VARCHAR(64),             -- 复式记账 txnId

    -- 状态
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING | SETTLED | FAILED

    -- 统计周期
    period_start    TIMESTAMPTZ NOT NULL,
    period_end      TIMESTAMPTZ NOT NULL,
    settled_at      TIMESTAMPTZ,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_settlement_station ON claw.revenue_settlements (station_id)       WHERE deleted = FALSE;
CREATE INDEX idx_settlement_date    ON claw.revenue_settlements (settlement_date) WHERE deleted = FALSE;
CREATE INDEX idx_settlement_status  ON claw.revenue_settlements (status)          WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 7. 触发器：入池时自动创建分成规则
--    资产入池时自动生成默认分成规则（所有人 70% / 站点 15% / 平台 10% / 保险 5%）
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_create_split_rule_on_pool()
RETURNS TRIGGER AS $$
BEGIN
    -- 仅在首次入池（IN_POOL）时创建分成规则
    IF NEW.status = 'IN_POOL' AND (TG_OP = 'INSERT' OR OLD.status <> 'IN_POOL') THEN
        INSERT INTO claw.revenue_split_rules
            (asset_id, pool_entry_id, owner_rate, station_rate, platform_rate, insurance_rate,
             share_basis, effective_from, status)
        VALUES
            (NEW.asset_id, NEW.id, NEW.owner_split_rate, NEW.station_split_rate,
             0.10, 0.05, 'PER_SWAP', CURRENT_DATE, 'ACTIVE')
        ON CONFLICT DO NOTHING;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pool_create_split
    AFTER INSERT OR UPDATE OF status ON claw.shared_pool_entries
    FOR EACH ROW EXECUTE FUNCTION claw.fn_create_split_rule_on_pool();

-- ---------------------------------------------------------------------
-- 8. 触发器：租赁完成时自动分账
--    订单 COMPLETED 时根据分成规则自动计算各方分成金额
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_calc_rental_split()
RETURNS TRIGGER AS $$
DECLARE
    v_owner_rate   NUMERIC(6,4);
    v_station_rate NUMERIC(6,4);
BEGIN
    IF NEW.status = 'COMPLETED' AND (OLD.status IS NULL OR OLD.status <> 'COMPLETED') THEN
        -- 查找生效分成规则
        SELECT owner_rate, station_rate INTO v_owner_rate, v_station_rate
        FROM claw.revenue_split_rules
        WHERE asset_id = NEW.asset_id
          AND status = 'ACTIVE'
          AND deleted = FALSE
        ORDER BY created_at DESC
        LIMIT 1;

        IF v_owner_rate IS NULL THEN
            v_owner_rate := 0.70;
            v_station_rate := 0.15;
        END IF;

        NEW.owner_share    := NEW.total_fee * v_owner_rate;
        NEW.station_share  := NEW.total_fee * v_station_rate;
        NEW.platform_share := NEW.total_fee * 0.10;
        NEW.insurance_share := NEW.total_fee * 0.05;
        NEW.completed_at   := now();
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rental_calc_split
    BEFORE UPDATE OF status ON claw.rental_orders
    FOR EACH ROW EXECUTE FUNCTION claw.fn_calc_rental_split();
