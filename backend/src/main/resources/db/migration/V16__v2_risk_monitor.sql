-- =====================================================================
-- Claw 平台 V16 增量表（v2.0 — 风控监控 + 熔断 + 保险基金）
-- 依据：《PRD v2.0》4.15/4.20 + D49-D51 + 《全风险规避方案 v2.0》R1-R8
-- 范围：
--   · station_risk_monitor: 站点风控指标监控（保证金缺口/资产异常/对账失败）
--   · insurance_fund: 保险基金池（平台保底持有30%覆盖率，R8 兜底）
--   · platform_risk_config: 风控阈值配置（可调参数，运营管理）
--   · audit_trail: 全局审计日志（关键操作留痕）
-- 对应 R1: 实时监控 + 自动熔断
-- 对应 R8: 平台保底持有30%（保险基金兜底）
-- 对应 D51: 风控完整版（监控+熔断+黑名单+保险基金）
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 站点风控监控（station_risk_monitor）
--    每个站点多个风控指标的实时状态
--    对应 PRD 4.15: 资金不过站 + 异常自动冻结
--    对应 D49: 风控监控仪表盘
-- ---------------------------------------------------------------------
CREATE TABLE claw.station_risk_monitor (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    station_id      BIGINT NOT NULL REFERENCES claw.stations (id),
    operator_id     BIGINT REFERENCES claw.users (id),

    -- 风控指标
    metric_type     VARCHAR(32) NOT NULL,
    -- BOND_SHORTFALL: 保证金缺口
    -- ASSET_MISMATCH: 资产盘点差异（实际 vs 系统）
    -- RECONCILIATION_FAIL: 对账失败
    -- COMPLAINT_SPIKE: 投诉激增（24h > 5条）
    -- TRANSACTION_ANOMALY: 交易异常（金额/频率偏离基线）
    -- OFF_HOURS_ACTIVITY: 非营业时间操作
    -- FUND_FLOW_ANOMALY: 资金流向异常

    -- 指标值
    metric_value    NUMERIC(18,4),     -- 当前指标值
    threshold       NUMERIC(18,4),     -- 阈值
    baseline        NUMERIC(18,4),     -- 基线值（历史平均）

    -- 风险评分
    risk_score      INTEGER NOT NULL DEFAULT 0,  -- 0-100，0=安全，100=极危险

    -- 状态
    status          VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    -- NORMAL: 正常
    -- WARNING: 预警（接近阈值 80%）
    -- CRITICAL: 临界（超过阈值）
    -- CIRCUIT_BREAK: 已熔断（自动冻结）
    -- RESOLVED: 已解除

    -- 触发与解除
    triggered_at    TIMESTAMPTZ,
    triggered_reason TEXT,
    resolved_at     TIMESTAMPTZ,
    resolved_by     BIGINT REFERENCES claw.users (id),
    resolution_note  TEXT,

    -- 关联风控事件
    risk_event_id   BIGINT,            -- 关联 V10 operator_risk_events.id

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_risk_station ON claw.station_risk_monitor (station_id) WHERE deleted = FALSE AND status IN ('WARNING','CRITICAL','CIRCUIT_BREAK');
CREATE INDEX idx_risk_metric  ON claw.station_risk_monitor (metric_type) WHERE deleted = FALSE;
CREATE INDEX idx_risk_status   ON claw.station_risk_monitor (status)     WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 2. 保险基金池（insurance_fund）
--    R8: 平台保底持有30%覆盖率
--    来源：每笔交易自动计提 5% 保险分成
--    用途：理赔赔付 + 回收兜底
--    D48: 保险基金独立审计
-- ---------------------------------------------------------------------
CREATE TABLE claw.insurance_fund (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- 基金池余额
    total_balance   NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 当前基金总额
    total_collected NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 累计收取
    total_claimed   NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 累计赔付
    total_recovered NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 累计回收兜底

    -- 覆盖率
    coverage_ratio  NUMERIC(6,4) NOT NULL DEFAULT 0.10,  -- 目标覆盖率（总资产价值的10%）
    -- R8: 平台保底持有30% → 独立指标，coverage_ratio 是基金对总资产价值的比率

    -- 总资产价值（快照）
    total_asset_value NUMERIC(18,4) NOT NULL DEFAULT 0,  -- 平台所有资产总价值
    coverage_actual   NUMERIC(6,4) NOT NULL DEFAULT 0,   -- 实际覆盖率 = balance / total_asset_value

    -- 状态
    status          VARCHAR(16) NOT NULL DEFAULT 'HEALTHY',
    -- HEALTHY: 健康（覆盖率 >= target）
    -- LOW: 不足（覆盖率 < target）
    -- CRITICAL: 严重不足（覆盖率 < target * 50%）
    -- FROZEN: 基金冻结（审计中）

    -- 最后更新
    last_updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_updated_by  BIGINT REFERENCES claw.users (id),
    audit_notes      TEXT,

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 只允许一条活跃记录
CREATE UNIQUE INDEX uq_insurance_fund_active ON claw.insurance_fund ((1)) WHERE deleted = FALSE;

-- 种子数据：初始化保险基金
INSERT INTO claw.insurance_fund (total_balance, total_collected, total_claimed, total_recovered, coverage_ratio, status)
VALUES (0, 0, 0, 0, 0.10, 'HEALTHY')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. 风控阈值配置（platform_risk_config）
--    运营可调的风控参数（无需改代码）
--    D50: 风控参数可配置化
-- ---------------------------------------------------------------------
CREATE TABLE claw.platform_risk_config (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    config_key      VARCHAR(64) NOT NULL UNIQUE,
    config_value    VARCHAR(256) NOT NULL,
    config_type     VARCHAR(16) NOT NULL,  -- NUMBER | BOOLEAN | STRING | JSON

    -- 描述
    display_name    VARCHAR(128) NOT NULL,
    description     TEXT,
    category        VARCHAR(32) NOT NULL,  -- BOND | CREDIT | INSURANCE | MONITOR | THRESHOLD

    -- 约束
    min_value       NUMERIC(18,4),
    max_value       NUMERIC(18,4),

    -- 审计
    last_modified_by BIGINT REFERENCES claw.users (id),

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 种子数据：默认风控配置
INSERT INTO claw.platform_risk_config (config_key, config_value, config_type, display_name, description, category, min_value, max_value) VALUES
    ('BOND_BASE_ENTERPRISE', '2000', 'NUMBER', '企业站基础保证金', '企业站点站长基础保证金（USD）', 'BOND', 500, 10000),
    ('BOND_BASE_INDIVIDUAL', '500', 'NUMBER', '个人站基础保证金', '个人站点站长基础保证金（USD）', 'BOND', 100, 2000),
    ('BOND_BASE_PARTNER', '1000', 'NUMBER', '合作站基础保证金', '合作站点站长基础保证金（USD）', 'BOND', 200, 5000),
    ('BOND_RATE_ASSET', '0.05', 'NUMBER', '经手资产保证金率', '站长经手资产价值的保证金比率', 'BOND', 0.01, 0.20),
    ('CREDIT_MIN_OPERATOR', '650', 'NUMBER', '个人站最低信用分', '开设个人站所需的最低 Claw Score', 'CREDIT', 500, 850),
    ('CREDIT_LOW_BOND_MULTIPLIER', '1.5', 'NUMBER', '低信用保证金倍数', '信用分 < 650 时保证金倍率', 'CREDIT', 1.0, 3.0),
    ('INSURANCE_FUND_TARGET_RATIO', '0.10', 'NUMBER', '保险基金目标覆盖率', '保险基金余额 / 总资产价值的目标比率', 'INSURANCE', 0.05, 0.30),
    ('INSURANCE_PLATFORM_GUARANTEE', '0.30', 'NUMBER', '平台保底覆盖率', 'R8 平台保底持有比率', 'INSURANCE', 0.10, 0.50),
    ('MONITOR_COMPLAINT_THRESHOLD_24H', '5', 'NUMBER', '24小时投诉阈值', '24小时内投诉数超过此值触发预警', 'MONITOR', 3, 20),
    ('MONITOR_RECONCILIATION_FAIL_LIMIT', '3', 'NUMBER', '对账连续失败上限', '连续对账失败次数上限', 'MONITOR', 1, 10),
    ('MONITOR_OFF_HOURS_START', '22', 'NUMBER', '非营业时间开始', '非营业时间开始（24小时制）', 'MONITOR', 0, 23),
    ('MONITOR_OFF_HOURS_END', '6', 'NUMBER', '非营业时间结束', '非营业时间结束（24小时制）', 'MONITOR', 0, 23),
    ('CIRCUIT_BREAKER_AUTO_ENABLED', 'true', 'BOOLEAN', '自动熔断启用', '是否启用自动熔断（关闭则仅告警）', 'THRESHOLD', NULL, NULL),
    ('DEPOSIT_FIXED_RATIO', '0.30', 'NUMBER', '固定押金比例', 'D36 资产固定押金比例', 'BOND', 0.10, 0.50),
    ('SPLIT_OWNER_MIN', '0.50', 'NUMBER', '所有人分成下限', '所有人最低分成比例', 'THRESHOLD', 0.40, 0.80),
    ('SPLIT_STATION_MIN', '0.15', 'NUMBER', '站点分成下限', '站点最低分成比例', 'THRESHOLD', 0.10, 0.30),
    ('SPLIT_PLATFORM_FIXED', '0.10', 'NUMBER', '平台分成固定值', '平台固定分成比例', 'THRESHOLD', 0.05, 0.15),
    ('SPLIT_INSURANCE_FIXED', '0.05', 'NUMBER', '保险分成固定值', '保险固定分成比例', 'THRESHOLD', 0.03, 0.10)
ON CONFLICT (config_key) DO NOTHING;

-- ---------------------------------------------------------------------
-- 4. 全局审计日志（audit_trail）
--    关键操作留痕：资金操作/状态变更/配置修改/熔断动作
--    D51: 全局审计追溯
-- ---------------------------------------------------------------------
CREATE TABLE claw.audit_trail (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- 操作人
    actor_user_id   BIGINT REFERENCES claw.users (id),
    actor_role      VARCHAR(32),             -- 操作时角色

    -- 操作类型
    action_type     VARCHAR(32) NOT NULL,
    -- FUNDS_TRANSFER: 资金转账
    -- LEDGER_ENTRY: 账本入账
    -- STATUS_CHANGE: 状态变更
    -- CONFIG_UPDATE: 风控配置修改
    -- CIRCUIT_BREAK: 熔断动作
    -- ROLE_GRANT: 角色授权
    -- RECOVERY: 回收操作
    -- INSURANCE_CLAIM: 保险理赔
    -- CUSTODY_TRANSFER: 管理权转移

    -- 操作目标
    target_entity   VARCHAR(32) NOT NULL,   -- 被操作的实体表名
    target_id       BIGINT,                  -- 被操作的记录 ID

    -- 操作详情
    action_detail   TEXT NOT NULL,
    before_snapshot JSONB,                   -- 变更前快照
    after_snapshot  JSONB,                   -- 变更后快照

    -- 环境
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(256),

    -- 时间
    acted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_audit_actor  ON claw.audit_trail (actor_user_id) WHERE deleted = FALSE;
CREATE INDEX idx_audit_action  ON claw.audit_trail (action_type)  WHERE deleted = FALSE;
CREATE INDEX idx_audit_target  ON claw.audit_trail (target_entity, target_id) WHERE deleted = FALSE;
CREATE INDEX idx_audit_time    ON claw.audit_trail (acted_at DESC) WHERE deleted = FALSE;

-- ---------------------------------------------------------------------
-- 5. 触发器：风控指标自动评分
--    metric_value 超过 threshold 的 80% → WARNING
--    超过 threshold → CRITICAL
--    超过 threshold 的 150% → CIRCUIT_BREAK
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_calc_risk_score()
RETURNS TRIGGER AS $$
DECLARE
    v_ratio NUMERIC;
BEGIN
    IF NEW.threshold IS NOT NULL AND NEW.threshold > 0 AND NEW.metric_value IS NOT NULL THEN
        v_ratio := NEW.metric_value / NEW.threshold;

        IF v_ratio >= 1.5 THEN
            NEW.status := 'CIRCUIT_BREAK';
            NEW.risk_score := LEAST(v_ratio * 30, 100);
            IF NEW.triggered_at IS NULL THEN
                NEW.triggered_at := now();
            END IF;
        ELSIF v_ratio >= 1.0 THEN
            NEW.status := 'CRITICAL';
            NEW.risk_score := LEAST(v_ratio * 30, 90);
            IF NEW.triggered_at IS NULL THEN
                NEW.triggered_at := now();
            END IF;
        ELSIF v_ratio >= 0.8 THEN
            NEW.status := 'WARNING';
            NEW.risk_score := LEAST(v_ratio * 30, 60);
        ELSE
            NEW.status := 'NORMAL';
            NEW.risk_score := LEAST(v_ratio * 30, 50);
        END IF;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_risk_score_calc
    BEFORE INSERT OR UPDATE OF metric_value ON claw.station_risk_monitor
    FOR EACH ROW EXECUTE FUNCTION claw.fn_calc_risk_score();

-- ---------------------------------------------------------------------
-- 6. 触发器：CIRCUIT_BREAK 时自动插入审计日志
--    熔断动作必须留痕
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_audit_circuit_break()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'CIRCUIT_BREAK' AND (OLD.status IS NULL OR OLD.status <> 'CIRCUIT_BREAK') THEN
        INSERT INTO claw.audit_trail
            (actor_user_id, actor_role, action_type, target_entity, target_id,
             action_detail, before_snapshot, after_snapshot, acted_at)
        VALUES
            (NEW.operator_id, 'SYSTEM', 'CIRCUIT_BREAK', 'station_risk_monitor', NEW.id,
             '站点 ' || NEW.station_id || ' 风控熔断: ' || NEW.metric_type ||
             ' 值=' || COALESCE(NEW.metric_value::TEXT, 'N/A') ||
             ' 阈值=' || COALESCE(NEW.threshold::TEXT, 'N/A') ||
             ' 原因=' || COALESCE(NEW.triggered_reason, '自动触发'),
             NULL,
             jsonb_build_object('station_id', NEW.station_id, 'metric_type', NEW.metric_type,
               'metric_value', NEW.metric_value, 'threshold', NEW.threshold,
               'risk_score', NEW.risk_score, 'status', NEW.status),
             now());
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_risk_audit_break
    AFTER UPDATE OF status ON claw.station_risk_monitor
    FOR EACH ROW EXECUTE FUNCTION claw.fn_audit_circuit_break();

-- ---------------------------------------------------------------------
-- 7. 触发器：保险基金覆盖率自动计算
--    coverage_actual = total_balance / total_asset_value
--    覆盖率不足时自动标记 LOW/CRITICAL
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.fn_calc_fund_coverage()
RETURNS TRIGGER AS $$
DECLARE
    v_coverage NUMERIC;
BEGIN
    IF NEW.total_asset_value > 0 THEN
        v_coverage := NEW.total_balance / NEW.total_asset_value;
        NEW.coverage_actual := v_coverage;

        IF v_coverage < NEW.coverage_ratio * 0.5 THEN
            NEW.status := 'CRITICAL';
        ELSIF v_coverage < NEW.coverage_ratio THEN
            NEW.status := 'LOW';
        ELSE
            NEW.status := 'HEALTHY';
        END IF;
    ELSE
        NEW.coverage_actual := 0;
        NEW.status := 'HEALTHY';
    END IF;

    NEW.last_updated_at := now();
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_fund_coverage_calc
    BEFORE INSERT OR UPDATE ON claw.insurance_fund
    FOR EACH ROW EXECUTE FUNCTION claw.fn_calc_fund_coverage();
