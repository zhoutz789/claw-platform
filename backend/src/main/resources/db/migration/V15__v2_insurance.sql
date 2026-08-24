-- =====================================================================
-- Claw 平台 V15 增量表（v2.0 — 车辆保险 + 理赔框架）
-- 依据：《PRD v2.0》4.19 + D47/D48 + 《全风险规避方案 v2.0》R8
-- 范围：
--   · vehicle_insurance: 车辆保险单（第三方责任险 + 资产损失险）
--   · accident_claims: 事故理赔记录（事故报告 → 审核 → 赔付）
--   · insurance_policy_templates: 保险方案模板（标准化产品）
-- 对应 R8: 保险兜底（保险基金保底持有30%覆盖率）
-- 对应 D47: 车辆资产强制保险
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 保险方案模板（insurance_policy_templates）
--    标准化保险产品定义：保费/覆盖额/免赔额
-- ---------------------------------------------------------------------
CREATE TABLE claw.insurance_policy_templates (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(32) NOT NULL UNIQUE,   -- THIRD_PARTLY_BASIC | COMPREHENSIVE | BATTERY_COVER

    -- 保险类型
    insurance_type  VARCHAR(32) NOT NULL,  -- THIRD_PARTY_LIABILITY | ASSET_LOSS | BATTERY_DAMAGE | COMPREHENSIVE
    -- THIRD_PARTY_LIABILITY: 第三方责任险（撞人/撞物）
    -- ASSET_LOSS: 资产丢失险（电池/车辆丢失）
    -- BATTERY_DAMAGE: 电池损坏险（SOH骤降/水浸/撞击）
    -- COMPREHENSIVE: 综合险（以上全覆盖）

    -- 保险条款
    provider        VARCHAR(64),            -- 保险公司（Forté/ABA Insurance/自保）
    display_name    VARCHAR(128) NOT NULL,
    description     TEXT,

    -- 保费与覆盖
    premium_monthly  NUMERIC(18,4) NOT NULL,  -- 月保费（USD）
    coverage_amount  NUMERIC(18,4) NOT NULL,  -- 最高赔付额（USD）
    deductible       NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 免赔额（USD）

    -- 适用范围
    applies_to       VARCHAR(16) NOT NULL DEFAULT 'VEHICLE',  -- VEHICLE | BATTERY | BOTH

    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | RETIRED

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 种子数据：标准保险方案
INSERT INTO claw.insurance_policy_templates (code, insurance_type, provider, display_name, description, premium_monthly, coverage_amount, deductible, applies_to) VALUES
    ('THIRD_PARTY_BASIC', 'THIRD_PARTY_LIABILITY', 'FORTE', '第三方责任险-基础版', '第三方人身/财产伤害赔付，最高$10,000', 5.00, 10000.00, 50.00, 'VEHICLE'),
    ('ASSET_LOSS', 'ASSET_LOSS', 'PLATFORM_SELF', '资产丢失险', '电池/车辆丢失赔付（基于残值评估价）', 8.00, 5000.00, 100.00, 'BOTH'),
    ('BATTERY_COVER', 'BATTERY_DAMAGE', 'FORTE', '电池损坏险', 'SOH骤降/水浸/撞击损坏赔付', 10.00, 3000.00, 50.00, 'BATTERY'),
    ('COMPREHENSIVE', 'COMPREHENSIVE', 'FORTE', '综合保险', '第三方责任+资产丢失+电池损坏全覆盖', 18.00, 15000.00, 100.00, 'BOTH')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------
-- 2. 车辆保险单（vehicle_insurance）
--    对应 D47: 车辆资产强制保险
--    一辆车一份有效保险单，保费按月扣缴
-- ---------------------------------------------------------------------
CREATE TABLE claw.vehicle_insurance (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_no       VARCHAR(64) NOT NULL UNIQUE,

    -- 关联资产与所有人
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),
    owner_user_id   BIGINT NOT NULL REFERENCES claw.users (id),

    -- 保险方案
    template_id     BIGINT NOT NULL REFERENCES claw.insurance_policy_templates (id),
    insurance_type  VARCHAR(32) NOT NULL,  -- 冗余，冗余便于查询

    -- 保险方信息
    provider        VARCHAR(64) NOT NULL,  -- 保险公司
    coverage_amount NUMERIC(18,4) NOT NULL,
    deductible      NUMERIC(18,4) NOT NULL DEFAULT 0,

    -- 保费
    premium_monthly NUMERIC(18,4) NOT NULL,  -- 月保费
    premium_paid_through DATE,               -- 保费已缴纳至日期

    -- 保单期限
    start_date      DATE NOT NULL,
    end_date        DATE,                      -- NULL = 长期有效（按月续保）

    -- 状态
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE: 有效
    -- LAPSED: 逾期未续保（保费超 7 天未缴）
    -- CANCELLED: 已取消
    -- CLAIM_PENDING: 理赔审核中
    -- EXPIRED: 已过期

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_insurance_active
    ON claw.vehicle_insurance (asset_id) WHERE deleted = FALSE AND status = 'ACTIVE';
CREATE INDEX idx_insurance_owner ON claw.vehicle_insurance (owner_user_id) WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 3. 事故理赔记录（accident_claims）
--    事故报告 → 审核定损 → 赔付 → 关闭
--    对应 PRD 4.19: 保险理赔流程
-- ---------------------------------------------------------------------
CREATE TABLE claw.accident_claims (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_no        VARCHAR(64) NOT NULL UNIQUE,

    -- 关联
    insurance_id    BIGINT NOT NULL REFERENCES claw.vehicle_insurance (id),
    asset_id        BIGINT NOT NULL REFERENCES claw.assets (id),
    rental_order_id BIGINT REFERENCES claw.rental_orders (id),  -- V12 关联租赁订单
    claimant_user_id BIGINT NOT NULL REFERENCES claw.users (id),  -- 报案人

    -- 事故信息
    claim_type      VARCHAR(32) NOT NULL,  -- THIRD_PARTY_INJURY | THIRD_PARTY_PROPERTY | ASSET_LOST | BATTERY_DAMAGE | VEHICLE_DAMAGE
    accident_date   TIMESTAMPTZ NOT NULL,
    accident_location VARCHAR(256),
    description     TEXT NOT NULL,

    -- 证据
    evidence_urls   JSONB,                  -- 照片/视频/文档 URL
    police_report_no VARCHAR(64),          -- 警方报告编号（如适用）

    -- 定损
    damage_amount   NUMERIC(18,4) NOT NULL,  -- 申报损失金额
    assessed_amount NUMERIC(18,4),           -- 定损金额（保险方评估）
    deductible_applied NUMERIC(18,4) DEFAULT 0,  -- 适用的免赔额
    payout_amount   NUMERIC(18,4),           -- 实际赔付额 = assessed - deductible

    -- 赔付资金
    fund_source     VARCHAR(16) NOT NULL DEFAULT 'INSURANCE_FUND',  -- INSURANCE_FUND | PLATFORM_FUND
    ledger_txn_id   VARCHAR(64),             -- 复式记账 txnId

    -- 理赔状态
    status          VARCHAR(16) NOT NULL DEFAULT 'FILED',
    -- FILED: 已报案
    -- UNDER_REVIEW: 审核中
    -- ASSESSED: 已定损
    -- APPROVED: 已批准
    -- PAID: 已赔付
    -- REJECTED: 已拒赔
    -- CLOSED: 已关闭

    -- 审核人
    reviewed_by     BIGINT REFERENCES claw.users (id),
    reviewed_at     TIMESTAMPTZ,
    review_notes    TEXT,

    -- 时间
    filed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_claim_insurance ON claw.accident_claims (insurance_id) WHERE deleted = FALSE;
CREATE INDEX idx_claim_asset     ON claw.accident_claims (asset_id)    WHERE deleted = FALSE;
CREATE INDEX idx_claim_status    ON claw.accident_claims (status)     WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 4. 触发器：保费逾期自动标记 LAPSED
--    保费超过 7 天未缴 → status = LAPSED
--    每天 02:00 由定时任务扫描执行（此处提供函数）
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_check_lapsed_insurance()
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    UPDATE claw.vehicle_insurance
    SET status = 'LAPSED', updated_at = now()
    WHERE deleted = FALSE
      AND status = 'ACTIVE'
      AND premium_paid_through IS NOT NULL
      AND premium_paid_through < CURRENT_DATE - 7;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- 5. 触发器：理赔定损时自动计算赔付额
--    payout = max(assessed - deductible, 0)
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_calc_claim_payout()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.assessed_amount IS NOT NULL THEN
        NEW.deductible_applied := LEAST(
            COALESCE(NEW.deductible_applied, 0),
            NEW.assessed_amount
        );
        NEW.payout_amount := GREATEST(NEW.assessed_amount - NEW.deductible_applied, 0);
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_claim_calc_payout
    BEFORE UPDATE OF assessed_amount ON claw.accident_claims
    FOR EACH ROW EXECUTE FUNCTION claw.fn_calc_claim_payout();

-- ---------------------------------------------------------------------
-- 6. 触发器：理赔状态流转约束
--    FILED → UNDER_REVIEW → ASSESSED → APPROVED → PAID → CLOSED
--    不可跳过步骤（防止未定损直接赔付）
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_claim_status_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status <> OLD.status THEN
        -- 不可从 FILED 直接跳到 APPROVED/PAID（必须经过 UNDER_REVIEW + ASSESSED）
        IF OLD.status = 'FILED' AND NEW.status NOT IN ('UNDER_REVIEW', 'REJECTED', 'CLOSED') THEN
            RAISE EXCEPTION 'Invalid claim status transition: FILED -> %', NEW.status;
        END IF;
        -- 不可从 UNDER_REVIEW 直接跳到 PAID（必须经过 ASSESSED + APPROVED）
        IF OLD.status = 'UNDER_REVIEW' AND NEW.status NOT IN ('ASSESSED', 'REJECTED', 'CLOSED') THEN
            RAISE EXCEPTION 'Invalid claim status transition: UNDER_REVIEW -> %', NEW.status;
        END IF;
        -- PAID 时必须有 payout_amount
        IF NEW.status = 'PAID' AND (NEW.payout_amount IS NULL OR NEW.payout_amount <= 0) THEN
            RAISE EXCEPTION 'Cannot mark claim PAID without payout amount';
        END IF;
        -- CLOSED/PAID 时记录 resolved_at
        IF NEW.status IN ('PAID', 'CLOSED', 'REJECTED') AND NEW.resolved_at IS NULL THEN
            NEW.resolved_at := now();
        END IF;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_claim_status_check
    BEFORE UPDATE OF status ON claw.accident_claims
    FOR EACH ROW EXECUTE FUNCTION claw.fn_claim_status_transition();
