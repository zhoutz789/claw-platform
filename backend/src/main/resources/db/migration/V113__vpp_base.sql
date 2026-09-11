SET search_path = claw;

-- 虚拟电厂（VPP）基础表（VPP 切片第一批）。
--
-- 业务前提：柬埔寨没有需求响应/辅助服务市场，参与电网调度不产生收益，
-- 因此本 VPP 是「私域自用型」——把光伏、储能、充电桩、柴油机组聚起来做
-- 站内自用优化（柴油替代 + 需量管理 + 自发自用最大化）。
-- 对外电网/DR 接口只在服务层保留抽象，本期不落表、不实现。

-- 虚拟电厂（资源组合/portfolio）
CREATE TABLE IF NOT EXISTS vpp_portfolios (
    id                          BIGSERIAL PRIMARY KEY,
    name                        VARCHAR(120) NOT NULL,
    operator_id                 BIGINT,
    region_code                 VARCHAR(32),
    grid_node                   VARCHAR(64),          -- 并网点
    target_self_consumption_rate NUMERIC(5,4),        -- 目标自发自用率 0–1
    status                      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_id                   BIGINT NOT NULL DEFAULT 1,
    created_at                  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vpp_portfolios_status ON vpp_portfolios (status);

-- VPP 资源（一个资产只能注册成一个资源，故 asset_id 唯一）
CREATE TABLE IF NOT EXISTS vpp_resources (
    id              BIGSERIAL PRIMARY KEY,
    portfolio_id    BIGINT NOT NULL,
    asset_id        BIGINT NOT NULL,
    resource_type   VARCHAR(24) NOT NULL,   -- PV / ESS / CHARGER / DIESEL_GEN / CONTROLLABLE_LOAD
    rated_power_w   NUMERIC(12,2),
    adjustable_min_w NUMERIC(12,2),
    adjustable_max_w NUMERIC(12,2),
    response_seconds INTEGER,               -- 响应时延（秒）
    grid_node       VARCHAR(64),
    status          VARCHAR(16) NOT NULL DEFAULT 'ONLINE',
    tenant_id       BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_vpp_resources_asset ON vpp_resources (asset_id);
CREATE INDEX IF NOT EXISTS idx_vpp_resources_portfolio ON vpp_resources (portfolio_id);

-- 调度指令（shadow=true 表示影子模式：只留痕，不下发）
CREATE TABLE IF NOT EXISTS vpp_dispatch_orders (
    id            BIGSERIAL PRIMARY KEY,
    portfolio_id  BIGINT NOT NULL,
    resource_id   BIGINT NOT NULL,
    command_type  VARCHAR(32) NOT NULL,     -- DERATE_PV / CHARGE_ESS / DISCHARGE_ESS / CURTAIL_CHARGER / START_GEN
    target_w      NUMERIC(12,2),
    start_at      TIMESTAMP,
    end_at        TIMESTAMP,
    status        VARCHAR(16) NOT NULL DEFAULT 'ISSUED',  -- ISSUED/ACKED/EXECUTING/DONE/FAILED/EXPIRED
    shadow        BOOLEAN NOT NULL DEFAULT false,
    issued_by     BIGINT,
    reason        VARCHAR(255),
    tenant_id     BIGINT NOT NULL DEFAULT 1,
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vpp_orders_portfolio_status ON vpp_dispatch_orders (portfolio_id, status);
CREATE INDEX IF NOT EXISTS idx_vpp_orders_resource_created ON vpp_dispatch_orders (resource_id, created_at DESC);

-- 调度执行结果（合规率采样）
CREATE TABLE IF NOT EXISTS vpp_dispatch_results (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT NOT NULL,
    sample_at       TIMESTAMP,
    actual_w        NUMERIC(12,2),
    deviation_w     NUMERIC(12,2),
    compliance_rate NUMERIC(6,4),
    remark          VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vpp_results_order_sample ON vpp_dispatch_results (order_id, sample_at);
