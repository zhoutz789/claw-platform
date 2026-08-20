-- =====================================================================
-- Claw 平台 V5 增量表（S2：站点现货 / 押金流转 / 合规 DTI）
-- 依据：《技术开发文档 v0.4》2.2 + 周老板 UI 验收结论：
--   · 客户选购 = 附近站点现货（搜索车型 → 有现货的站点；或逛附近站点）
--   · 投资者认购车辆投放站点（智能分配/指定站）成为现货
--   · 押金流转：换电押金冻结 → 退还/扣收 → 残值准备金专户
--   · 合规红线：DTI≤50% 强制校验（负责任信贷）
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 站点（domain.station）：换电/现货自提/交付网点
--    country_code 关联 countries（法域），法域切换即站点切换
-- ---------------------------------------------------------------------
CREATE TABLE claw.stations (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code           VARCHAR(64) NOT NULL UNIQUE,
    name           VARCHAR(128) NOT NULL,
    area           VARCHAR(128),
    country_code   CHAR(3)     NOT NULL DEFAULT 'KHM' REFERENCES claw.countries (code),
    province       VARCHAR(64),
    city           VARCHAR(64),
    district       VARCHAR(64),
    lat            NUMERIC(10,6),
    lng            NUMERIC(10,6),
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | CLOSED | BUILDING
    open_hours     VARCHAR(32) NOT NULL DEFAULT '24H',
    operator_id    BIGINT REFERENCES claw.users (id),      -- 站方（人人经济 STATION_OWNER）
    tenant_id      BIGINT      NOT NULL DEFAULT 1,
    deleted        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stations_country ON claw.stations (country_code, status);

-- 站点现货库存：投资者认购投放的车辆/电池在该站可选购/可取
CREATE TABLE claw.station_stock (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    station_id  BIGINT      NOT NULL REFERENCES claw.stations (id),
    sku_code    VARCHAR(64) NOT NULL,            -- 厂家产品 SKU（V6 引入 factory_products 后建外键）
    stock_qty   INT         NOT NULL DEFAULT 0 CHECK (stock_qty >= 0),
    tenant_id   BIGINT      NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (station_id, sku_code)
);

-- ---------------------------------------------------------------------
-- 2. 押金单（domain.deposit）：电池/车辆押金冻结与流转
--    流转链路：客户支付押金 → DEPOSIT_LOCKED 冻结 → 归还时解冻退还，
--    或违约/损坏时扣收转入残值准备金专户（RESIDUAL_RESERVE，D27 口径）
-- ---------------------------------------------------------------------
CREATE TABLE claw.deposits (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deposit_no     VARCHAR(64) NOT NULL UNIQUE,
    user_id        BIGINT      NOT NULL REFERENCES claw.users (id),
    asset_id       BIGINT      REFERENCES claw.assets (id),   -- 押金针对的资产（电池/车辆）
    amount         NUMERIC(16,2) NOT NULL CHECK (amount > 0),
    status         VARCHAR(16) NOT NULL DEFAULT 'HELD',       -- HELD 冻结 | RETURNED 已退 | FORFEITED 扣收
    pay_order_no   VARCHAR(64),                               -- 关联支付单
    forfeit_reason VARCHAR(255),
    tenant_id      BIGINT      NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deposits_user ON claw.deposits (user_id, status);

-- ---------------------------------------------------------------------
-- 3. 合规检查记录（domain.compliance）：负责任信贷红线留痕
--    DTI（月债务/月净收入）≤50% 强制；银行流水/收入证明为人工复核依据
-- ---------------------------------------------------------------------
CREATE TABLE claw.compliance_checks (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES claw.users (id),
    check_type      VARCHAR(32) NOT NULL,        -- DTI_CHECK 负债收入比 | LICENSE_REPORT 牌照申报
    monthly_debt    NUMERIC(16,2) NOT NULL DEFAULT 0,  -- 月债务（月供+预估换电等）
    monthly_income  NUMERIC(16,2) NOT NULL DEFAULT 0,  -- 月净收入
    dti_rate        NUMERIC(5,4),                -- DTI = monthly_debt / monthly_income
    result          VARCHAR(16) NOT NULL,        -- PASS | REJECT
    evidence        JSONB,                       -- 复核依据（银行流水/收入证明引用）
    checked_by      BIGINT REFERENCES claw.users (id),  -- 复核人（平台风控/人工）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_compliance_user ON claw.compliance_checks (user_id, check_type, created_at DESC);

-- ---------------------------------------------------------------------
-- 4. 种子数据
-- ---------------------------------------------------------------------

-- 金边附近 3 站（对齐 UI 原型 global-network / 附近站点现货验收结论）
INSERT INTO claw.stations (code, name, area, country_code, province, city, lat, lng, status, open_hours) VALUES
    ('PP-ROUSSEY', '俄罗斯市场站', 'Toul Kork',  'KHM', 'Phnom Penh', 'Phnom Penh', 11.5657, 104.8952, 'ACTIVE', '24H'),
    ('PP-CENTRAL', '中央市场站',   'Phsar Thmei', 'KHM', 'Phnom Penh', 'Phnom Penh', 11.5686, 104.9210, 'ACTIVE', '24H'),
    ('PP-AIRPORT', '机场站',       'Phnom Penh',  'KHM', 'Phnom Penh', 'Phnom Penh', 11.5466, 104.8442, 'ACTIVE', '06:00-24:00');

-- 站内现货初始库存（对齐 UI 原型 stock 数据：5 个厂家 SKU，V6 factory_products 后改为 sku_code 引用）
INSERT INTO claw.station_stock (station_id, sku_code, stock_qty)
SELECT s.id, v.sku, v.qty FROM (VALUES
    ('PP-ROUSSEY','BYD-C1',2), ('PP-ROUSSEY','YADEA-T2',1),
    ('PP-CENTRAL','BYD-C1-PRO',3), ('PP-CENTRAL','YADEA-T2',2), ('PP-CENTRAL','YADEA-T2-MAX',1),
    ('PP-AIRPORT','BYD-C1',1), ('PP-AIRPORT','TAILG-PRO',1)
) AS v(site, sku, qty) JOIN claw.stations s ON s.code = v.site;

-- 平台内部主账户 + 三专户（D27 escrow 受托口径：资金所有权归用户/投资者）
INSERT INTO claw.accounts (account_type, currency) VALUES
    ('MASTER',            'USD'),
    ('RESIDUAL_RESERVE',  'USD'),
    ('BATTERY_FUND',      'USD'),
    ('VEHICLE_RISK',      'USD');

INSERT INTO claw.escrow_accounts (escrow_type, custodian_legal_form, account_ownership, status)
SELECT t.et, 'ESCROW', 'USER', 'PENDING' FROM (VALUES
    ('RESIDUAL_RESERVE'), ('BATTERY_FUND'), ('VEHICLE_RISK')
) AS t(et);
