-- =====================================================================
-- Claw 平台 V13 增量表（v2.0 — 残值回收引擎 + 信用基础设施）
-- 依据：《PRD v2.0》4.18 + D41/D42 + 《全风险规避方案 v2.0》R7
-- 范围：
--   · residual_valuations: 残值评估（三方估价：系统/站方/第三方）
--   · recovery_orders: 回收订单（现金回收 / 以旧换新）
--   · trade_in_orders: 以旧换新订单（旧资产 → 新资产补差价）
--   · claw_scores: 用户信用分（Claw Score 0-1000）
--   · station_blacklist: 站点黑名单（违规用户/站点拉黑）
-- 对应修改7：资产使用寿命不设限 → 残值回收闭环
-- 对应 R7：第三方残值评估（防止平台/站方操纵估价）
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 残值评估（residual_valuations）
--    三方估价机制（R7）：系统估价 + 站方估价 + 第三方估价
--    最终回收价 = 三方中位数（防操纵）
--    对应 PRD 4.18：残值回收
-- ---------------------------------------------------------------------
CREATE TABLE claw.residual_valuations (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),
    owner_user_id   BIGINT NOT NULL REFERENCES claw.users (id),

    -- 资产快照
    soh             NUMERIC(6,2),           -- 电池健康度 %
    usage_years     NUMERIC(6,2),           -- 使用年限
    brand           VARCHAR(64),
    model           VARCHAR(64),
    cycle_count     INTEGER,                -- 循环次数

    -- 三方估价（R7 防操纵）
    system_estimate     NUMERIC(18,4),      -- 系统估价（基于 SOH + 循环次数算法）
    station_estimate   NUMERIC(18,4),      -- 站方估价（站长现场评估）
    third_party_estimate NUMERIC(18,4),    -- 第三方估价（独立评估机构）

    -- 第三方评估信息
    third_party_name   VARCHAR(128),        -- 第三方评估机构名称
    third_party_report_url VARCHAR(256),    -- 评估报告 URL

    -- 最终价格
    final_price    NUMERIC(18,4),           -- 最终回收价（三方中位数）

    -- 状态
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- PENDING: 待评估
    -- SYSTEM_DONE: 系统估价完成
    -- STATION_DONE: 站方估价完成
    -- THIRD_PARTY_DONE: 三方估价完成
    -- FINALIZED: 最终价格确定
    -- EXPIRED: 评估过期（30天未确认）
    -- RECOVERED: 已回收入账

    evaluated_by      BIGINT REFERENCES claw.users (id),
    evaluated_at      TIMESTAMPTZ,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_valuation_asset  ON claw.residual_valuations (asset_id)     WHERE deleted = FALSE;
CREATE INDEX idx_valuation_owner  ON claw.residual_valuations (owner_user_id) WHERE deleted = FALSE;
CREATE INDEX idx_valuation_status ON claw.residual_valuations (status)        WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 2. 回收订单（recovery_orders）
--    资产所有人发起回收 → 三方估价 → 确认价格 → 入账
--    对应修改7：资产不设寿命 → 任何时候均可回收
--    回收资金从平台保底持有专户支出（V16 insurance_fund 关联）
-- ---------------------------------------------------------------------
CREATE TABLE claw.recovery_orders (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_no        VARCHAR(64) NOT NULL UNIQUE,

    -- 资产与所有人
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),
    owner_user_id   BIGINT NOT NULL REFERENCES claw.users (id),
    ownership_id    BIGINT REFERENCES claw.asset_ownership (id),  -- V12 关联产权记录

    -- 评估关联
    valuation_id    BIGINT NOT NULL REFERENCES claw.residual_valuations (id),

    -- 回收类型
    recovery_type   VARCHAR(16) NOT NULL,  -- CASH_RECOVERY | TRADE_IN
    -- CASH_RECOVERY: 现金回收（直接回收资金到用户账户）
    -- TRADE_IN: 以旧换新（回收旧资产 + 购买新资产，补差价）

    -- 回收金额
    recovery_price  NUMERIC(18,4) NOT NULL,  -- 回收价格（= valuation.final_price）
    processing_fee  NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 手续费
    net_amount      NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 实际到账 = recovery_price - fee

    -- 以旧换新关联
    new_asset_id    BIGINT REFERENCES claw.assets (id),  -- 新资产
    new_asset_price NUMERIC(18,4),                        -- 新资产价格
    price_difference NUMERIC(18,4),                       -- 差价 = new_price - recovery_price

    -- 资金路径
    fund_source     VARCHAR(16) NOT NULL DEFAULT 'PLATFORM_FUND',  -- PLATFORM_FUND | INSURANCE_FUND
    ledger_txn_id   VARCHAR(64),            -- 复式记账 txnId

    -- 状态
    status          VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    -- CREATED | VALUATION_PENDING | VALUATION_DONE | OWNER_CONFIRMED
    -- | PROCESSING | COMPLETED | CANCELLED | FAILED

    confirmed_at    TIMESTAMPTZ,            -- 所有人确认时间
    completed_at    TIMESTAMPTZ,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_recovery_asset  ON claw.recovery_orders (asset_id)       WHERE deleted = FALSE;
CREATE INDEX idx_recovery_owner  ON claw.recovery_orders (owner_user_id)   WHERE deleted = FALSE;
CREATE INDEX idx_recovery_status ON claw.recovery_orders (status)         WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 3. 以旧换新订单（trade_in_orders）
--    旧资产回收 + 新资产购买 = 一笔合并交易
--    差价 = 新资产价格 - 旧资产回收价
-- ---------------------------------------------------------------------
CREATE TABLE claw.trade_in_orders (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_no        VARCHAR(64) NOT NULL UNIQUE,

    -- 旧资产
    old_asset_id    BIGINT NOT NULL REFERENCES claw.assets (id),
    old_valuation   NUMERIC(18,4) NOT NULL,  -- 旧资产评估价

    -- 新资产
    new_asset_id    BIGINT NOT NULL REFERENCES claw.assets (id),
    new_price       NUMERIC(18,4) NOT NULL,  -- 新资产售价

    -- 差价
    price_difference NUMERIC(18,4) NOT NULL,  -- = new_price - old_valuation（用户需补）

    -- 关联回收订单
    recovery_order_id BIGINT REFERENCES claw.recovery_orders (id),

    -- 用户
    owner_user_id   BIGINT NOT NULL REFERENCES claw.users (id),

    -- 状态
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- PENDING | PAID | COMPLETED | CANCELLED

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tradein_user ON claw.trade_in_orders (owner_user_id) WHERE deleted = FALSE;
CREATE INDEX idx_tradein_old  ON claw.trade_in_orders (old_asset_id)   WHERE deleted = FALSE;
CREATE INDEX idx_tradein_new  ON claw.trade_in_orders (new_asset_id)   WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 4. 用户信用分（claw_scores）
--    Claw Score 0-1000，用于：
--      · 个人站准入门槛（R1: score >= 650 才能开站）
--      · 押金动态核定（R3: 低分用户提高押金比例）
--      · 风控熔断阈值（R1: 低分用户降低熔断阈值）
-- ---------------------------------------------------------------------
CREATE TABLE claw.claw_scores (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES claw.users (id),

    -- 信用分
    score           INTEGER NOT NULL DEFAULT 650,  -- 0-1000，默认 650（中等）
    score_level     VARCHAR(16) NOT NULL DEFAULT 'C',
    -- A: 850-1000（优秀）
    -- B: 750-849（良好）
    -- C: 650-749（中等）
    -- D: 500-649（较差）
    -- E: 0-499（极差）

    -- 信用因子
    on_time_payment_rate  NUMERIC(6,4) NOT NULL DEFAULT 1.00,  -- 按时付款率
    asset_loss_rate       NUMERIC(6,4) NOT NULL DEFAULT 0.00,  -- 资产丢失率
    complaint_count       INTEGER NOT NULL DEFAULT 0,           -- 投诉次数
    operating_months      INTEGER NOT NULL DEFAULT 0,            -- 运营月数

    -- 最后更新原因
    last_event_type   VARCHAR(32),   -- PAYMENT_ON_TIME | PAYMENT_LATE | ASSET_LOSS | COMPLAINT | RECOVERY | MONTHLY_REVIEW
    last_event_detail TEXT,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_claw_score_user ON claw.claw_scores (user_id) WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 5. 站点黑名单（station_blacklist）
--    用户/站点违规后拉黑，禁止使用/运营
--    对应 R1: 实时监控 → 自动/手动拉黑
-- ---------------------------------------------------------------------
CREATE TABLE claw.station_blacklist (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    station_id      BIGINT REFERENCES claw.stations (id),
    user_id         BIGINT REFERENCES claw.users (id),

    -- 拉黑类型
    blacklist_type  VARCHAR(16) NOT NULL,  -- USER_BANNED | STATION_BANNED
    -- USER_BANNED: 用户被拉黑（禁止使用平台）
    -- STATION_BANNED: 站点被拉黑（禁止运营）

    -- 拉黑原因
    reason          VARCHAR(128) NOT NULL,  -- FRAUD | ASSET_LOSS | BOND_SHORTFALL | COMPLAINT_SPIKE | MANUAL
    description     TEXT,

    -- 时效
    blacklisted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,            -- NULL = 永久拉黑

    -- 审批
    blacklisted_by  BIGINT REFERENCES claw.users (id),  -- 操作人（风控官）
    resolved        BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_by     BIGINT REFERENCES claw.users (id),
    resolved_at     TIMESTAMPTZ,
    resolution_note TEXT,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_blacklist_user    ON claw.station_blacklist (user_id)    WHERE deleted = FALSE AND resolved = FALSE;
CREATE INDEX idx_blacklist_station ON claw.station_blacklist (station_id) WHERE deleted = FALSE AND resolved = FALSE;

-- ---------------------------------------------------------------------
-- 6. 触发器：信用分自动评级
--    score 变更时自动计算 score_level
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_calc_score_level()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.score >= 850 THEN
        NEW.score_level := 'A';
    ELSIF NEW.score >= 750 THEN
        NEW.score_level := 'B';
    ELSIF NEW.score >= 650 THEN
        NEW.score_level := 'C';
    ELSIF NEW.score >= 500 THEN
        NEW.score_level := 'D';
    ELSE
        NEW.score_level := 'E';
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_claw_score_level
    BEFORE INSERT OR UPDATE OF score ON claw.claw_scores
    FOR EACH ROW EXECUTE FUNCTION claw.fn_calc_score_level();

-- ---------------------------------------------------------------------
-- 7. 触发器：三方估价完成时自动计算最终价格（中位数）
--    R7 防操纵：最终价 = median(system, station, third_party)
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_calc_final_price()
RETURNS TRIGGER AS $$
DECLARE
    v_system     NUMERIC(18,4);
    v_station    NUMERIC(18,4);
    v_third      NUMERIC(18,4);
    v_count      INTEGER;
BEGIN
    v_system := NEW.system_estimate;
    v_station := NEW.station_estimate;
    v_third := NEW.third_party_estimate;

    SELECT (CASE
        WHEN v_system IS NOT NULL THEN 1 ELSE 0 END +
        CASE WHEN v_station IS NOT NULL THEN 1 ELSE 0 END +
        CASE WHEN v_third IS NOT NULL THEN 1 ELSE 0 END
    ) INTO v_count;

    -- 三方估价全部完成时自动计算中位数
    IF v_count = 3 THEN
        -- 中位数：三个值排序后取中间值
        NEW.final_price := (
            SELECT AVG(val) FROM (
                VALUES (v_system), (v_station), (v_third)
            ) AS t(val)
            ORDER BY val
            LIMIT 1 OFFSET 1
        );
        NEW.status := 'FINALIZED';
        NEW.evaluated_at := now();
    ELSIF v_count = 2 THEN
        -- 两方估价完成：取平均值
        NEW.final_price := (
            SELECT AVG(val) FROM (
                VALUES (v_system), (v_station), (v_third)
            ) AS t(val) WHERE val IS NOT NULL
        );
    END IF;

    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_valuation_final_price
    BEFORE UPDATE ON claw.residual_valuations
    FOR EACH ROW EXECUTE FUNCTION claw.fn_calc_final_price();

-- ---------------------------------------------------------------------
-- 8. 触发器：回收订单完成时自动更新产权状态
--    现金回收 → ownership.status = RECOVERED
--    以旧换新 → ownership.status = TRADED_IN
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_recovery_update_ownership()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'COMPLETED' AND (OLD.status IS NULL OR OLD.status <> 'COMPLETED') THEN
        IF NEW.ownership_id IS NOT NULL THEN
            UPDATE claw.asset_ownership
            SET status = CASE
                WHEN NEW.recovery_type = 'CASH_RECOVERY' THEN 'RECOVERED'
                WHEN NEW.recovery_type = 'TRADE_IN' THEN 'TRADED_IN'
            END,
            recovery_order_id = NEW.id,
            new_asset_id = NEW.new_asset_id,
            updated_at = now()
            WHERE id = NEW.ownership_id;
        END IF;
        NEW.completed_at := now();
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_recovery_update_ownership
    AFTER UPDATE OF status ON claw.recovery_orders
    FOR EACH ROW EXECUTE FUNCTION claw.fn_recovery_update_ownership();
