-- =====================================================================
-- Claw 平台 V14 增量表（v2.0 — 管理权转移产权链）
-- 依据：《PRD v2.0》4.17 + D45 + 《全风险规避方案 v2.0》N8/N3
-- 范围：
--   · custody_transfers: 产权链转移记录（D45 不可篡改追踪）
--   · custody_disputes: 争议仲裁记录
--   · custody_transfer_audit: 转移审计日志（防洗电池套利）
-- 对应修改6：每次换电产生一次管理权转移，需完整追踪链
-- 对应 T-A7/N3：管理权高频转移需独立追踪表
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 管理权转移记录（custody_transfers）
--    核心表：记录每次资产管理权从 A → B 的转移
--    修改6：换电时管理权从当前使用人 → 新使用人
--    D45：产权链不可篡改（hash 链式结构）
-- ---------------------------------------------------------------------
CREATE TABLE claw.custody_transfers (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- 资产标识
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),
    asset_type      VARCHAR(16) NOT NULL,  -- BATTERY | VEHICLE

    -- 转移方
    from_user_id    BIGINT REFERENCES claw.users (id),  -- 原管理方（首次入池时为 NULL）
    to_user_id      BIGINT NOT NULL REFERENCES claw.users (id),  -- 新管理方

    -- 转移场景
    transfer_type   VARCHAR(32) NOT NULL,  -- SWAP_EXCHANGE | RENTAL_START | RENTAL_END
                                           -- | SHARED_POOL_ENTRY | SHARED_POOL_EXIT
                                           -- | RECOVERY | TRADE_IN | INITIAL_PURCHASE
    station_id      BIGINT REFERENCES claw.stations (id),  -- 发生站点
    swap_order_id   BIGINT,                -- 关联换电订单（如适用）

    -- 产权链（D45 不可篡改）
    prev_transfer_id BIGINT REFERENCES claw.custody_transfers (id),  -- 前序转移（链式引用）
    chain_hash      VARCHAR(128) NOT NULL,  -- hash(prev_hash + transfer_data) 防篡改

    -- 资产状态快照（转移时刻）
    asset_soh       NUMERIC(6,2),           -- 电池健康度快照
    asset_soc       NUMERIC(6,2),           -- 电量快照
    asset_cycle_count INTEGER,             -- 循环次数快照

    -- 时间戳
    transferred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_custody_asset    ON claw.custody_transfers (asset_id)        WHERE deleted = FALSE;
CREATE INDEX idx_custody_from     ON claw.custody_transfers (from_user_id)    WHERE deleted = FALSE;
CREATE INDEX idx_custody_to       ON claw.custody_transfers (to_user_id)      WHERE deleted = FALSE;
CREATE INDEX idx_custody_chain    ON claw.custody_transfers (prev_transfer_id) WHERE deleted = FALSE;
CREATE INDEX idx_custody_type     ON claw.custody_transfers (transfer_type)   WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 2. 管理权争议仲裁（custody_disputes）
--    N8：管理权频繁转移可能产生争议
--    PRD 4.17：争议仲裁机制
-- ---------------------------------------------------------------------
CREATE TABLE claw.custody_disputes (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- 争议关联
    transfer_id     BIGINT NOT NULL REFERENCES claw.custody_transfers (id),
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),

    -- 争议方
    claimant_id     BIGINT NOT NULL REFERENCES claw.users (id),  -- 争议发起人
    respondent_id   BIGINT NOT NULL REFERENCES claw.users (id),  -- 争议被诉人

    -- 争议内容
    dispute_type    VARCHAR(32) NOT NULL,  -- OWNERSHIP_DISPUTE | DAMAGE_CLAIM | MISSING_ASSET
                                           -- | UNAUTHORIZED_TRANSFER | FEE_DISPUTE
    description     TEXT NOT NULL,
    evidence_urls   JSONB,                  -- 证据图片/文档 URL

    -- 涉及金额
    claim_amount    NUMERIC(18,4),          -- 索赔金额
    awarded_amount  NUMERIC(18,4),          -- 裁决金额

    -- 仲裁流程
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING | IN_REVIEW | RESOLVED | ESCALATED
    arbitrator_id   BIGINT REFERENCES claw.users (id),       -- 仲裁人
    resolution      TEXT,                   -- 裁决说明
    resolved_at     TIMESTAMPTZ,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dispute_transfer ON claw.custody_disputes (transfer_id) WHERE deleted = FALSE;
CREATE INDEX idx_dispute_status   ON claw.custody_disputes (status)      WHERE deleted = FALSE;
CREATE INDEX idx_dispute_claimant ON claw.custody_disputes (claimant_id) WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 3. 转移审计日志（custody_transfer_audit）
--    防洗电池套利：监控异常高频转移
--    N3：管理权高频转移监控
-- ---------------------------------------------------------------------
CREATE TABLE claw.custody_transfer_audit (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id     BIGINT NOT NULL REFERENCES claw.custody_transfers (id),
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),

    -- 异常检测
    anomaly_type    VARCHAR(32),   -- HIGH_FREQUENCY_TRANSFER | RAPID_CHURN | CIRCULAR_TRANSFER
                                    -- | OFF_HOURS_TRANSFER | VALUE_MISMATCH
    risk_score      INTEGER,        -- 风险评分 0-100
    description     TEXT,

    -- 关联统计
    user_daily_transfer_count  INTEGER,  -- 用户当日转移次数
    asset_daily_transfer_count INTEGER,  -- 资产当日转移次数

    detected_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed        BOOLEAN NOT NULL DEFAULT FALSE,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_asset  ON claw.custody_transfer_audit (asset_id)   WHERE deleted = FALSE;
CREATE INDEX idx_audit_anomaly ON claw.custody_transfer_audit (anomaly_type) WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 4. 触发器：自动计算产权链 chain_hash
--    D45 不可篡改：每次插入时计算 hash(prev_chain_hash + transfer_data)
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_calc_chain_hash()
RETURNS TRIGGER AS $$
DECLARE
    v_prev_hash VARCHAR(128);
    v_hash_input TEXT;
BEGIN
    -- 获取前序转移的 chain_hash
    IF NEW.prev_transfer_id IS NOT NULL THEN
        SELECT chain_hash INTO v_prev_hash
        FROM claw.custody_transfers
        WHERE id = NEW.prev_transfer_id;
    ELSE
        v_prev_hash := 'GENESIS';
    END IF;

    -- 计算 chain_hash = md5(prev_hash || asset_id || from_user || to_user || transfer_type || transferred_at)
    v_hash_input := COALESCE(v_prev_hash, 'GENESIS') || '|' ||
                    NEW.asset_id::TEXT || '|' ||
                    COALESCE(NEW.from_user_id::TEXT, 'NULL') || '|' ||
                    NEW.to_user_id::TEXT || '|' ||
                    NEW.transfer_type || '|' ||
                    NEW.transferred_at::TEXT;

    NEW.chain_hash := md5(v_hash_input);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_custody_chain_hash
    BEFORE INSERT ON claw.custody_transfers
    FOR EACH ROW EXECUTE FUNCTION claw.fn_calc_chain_hash();

-- ---------------------------------------------------------------------
-- 5. 触发器：插入转移记录时自动创建审计日志
--    N3：高频转移异常检测
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_audit_custody_transfer()
RETURNS TRIGGER AS $$
DECLARE
    v_user_daily_count INTEGER;
    v_asset_daily_count INTEGER;
BEGIN
    -- 统计当日转移次数
    SELECT COUNT(*) INTO v_user_daily_count
    FROM claw.custody_transfers
    WHERE to_user_id = NEW.to_user_id
      AND transferred_at::date = NEW.transferred_at::date
      AND deleted = FALSE;

    SELECT COUNT(*) INTO v_asset_daily_count
    FROM claw.custody_transfers
    WHERE asset_id = NEW.asset_id
      AND transferred_at::date = NEW.transferred_at::date
      AND deleted = FALSE;

    -- 用户日转移 > 10 次或资产日转移 > 5 次 → 记录异常
    IF v_user_daily_count > 10 OR v_asset_daily_count > 5 THEN
        INSERT INTO claw.custody_transfer_audit
            (transfer_id, asset_id, anomaly_type, risk_score, description,
             user_daily_transfer_count, asset_daily_transfer_count)
        VALUES
            (NEW.id, NEW.asset_id,
             CASE
                WHEN v_user_daily_count > 10 THEN 'HIGH_FREQUENCY_TRANSFER'
                WHEN v_asset_daily_count > 5 THEN 'RAPID_CHURN'
             END,
             LEAST(v_user_daily_count * 5 + v_asset_daily_count * 10, 100),
             '用户当日转移' || v_user_daily_count || '次, 资产当日转移' || v_asset_daily_count || '次',
             v_user_daily_count, v_asset_daily_count);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_custody_transfer_audit
    AFTER INSERT ON claw.custody_transfers
    FOR EACH ROW EXECUTE FUNCTION claw.fn_audit_custody_transfer();
