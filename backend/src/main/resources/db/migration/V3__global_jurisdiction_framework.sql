-- =====================================================================
-- Claw 平台 V3 增量表（全球化运营框架 · 多法域/多国家）
-- 依据：《PRD v1.1 决策 D33》全球共营框架：柬埔寨为首个试点(PILOT)，
--       后续接入东南亚→西亚→非洲→东欧（俄罗斯等），排除中国与发达国家。
-- 设计：核心业务域保持法域无关；身份/支付/牌照/运营主体/分润按国家以
--       「注册表 + 适配器」插件化，进一国只需插入数据，不动核心代码。
-- 通用规范继承 V1/V2：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 法域/国家主表（jurisdiction 域核心）
--    status: PILOT(首个试点) / ACTIVE(已运营) / PLANNED(规划中) / EXCLUDED(暂不做)
--    region: SEA / WEST_ASIA / AFRICA / EAST_EUROPE（扩展路线图分区）
--    data_residency: 该国是否要求数据留境（共营架构的数据主权约束）
-- ---------------------------------------------------------------------
CREATE TABLE claw.countries (
    code            VARCHAR(3)   PRIMARY KEY,            -- ISO-3166 alpha-3
    code_iso2       VARCHAR(2)   NOT NULL,
    name_en         VARCHAR(64)  NOT NULL,
    name_local      VARCHAR(64),
    region          VARCHAR(24)  NOT NULL,
    currency_code   VARCHAR(3)   NOT NULL,              -- 主结算币种
    default_locale  VARCHAR(8)   NOT NULL DEFAULT 'en',
    pilot_order     INT          NOT NULL DEFAULT 0,     -- 1=首个试点；其余按扩展波次
    status          VARCHAR(16)  NOT NULL,
    data_residency  BOOLEAN      NOT NULL DEFAULT TRUE,
    regulatory_note TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE claw.countries IS '法域/国家主表：共营框架的顶层维度，所有适配器按 country_code 挂载';

-- 种子：试点 + 目标国（PLANNED）+ 排除国（EXCLUDED）
-- 柬埔寨：首个试点
INSERT INTO claw.countries (code, code_iso2, name_en, name_local, region, currency_code, default_locale, pilot_order, status, data_residency, regulatory_note)
VALUES ('KHM', 'KH', 'Cambodia', 'កម្ពុជា', 'SEA', 'USD', 'en', 1, 'PILOT', TRUE,
        '首发试点：电力局充电设施运营牌照红利 + ABA 受托三专户 + CamDigiKey eKYC 已规划');

-- 东南亚（扩展波次 2）
INSERT INTO claw.countries (code, code_iso2, name_en, name_local, region, currency_code, pilot_order, status, data_residency) VALUES
('VNM', 'VN', 'Vietnam',     'Việt Nam',   'SEA', 'VND', 11, 'PLANNED', TRUE),
('IDN', 'ID', 'Indonesia',   '',           'SEA', 'IDR', 12, 'PLANNED', TRUE),
('PHL', 'PH', 'Philippines', '',           'SEA', 'PHP', 13, 'PLANNED', TRUE),
('THA', 'TH', 'Thailand',    '',           'SEA', 'THB', 14, 'PLANNED', TRUE),
('MMR', 'MM', 'Myanmar',     '',           'SEA', 'MMK', 15, 'PLANNED', TRUE),
('LAO', 'LA', 'Laos',        '',           'SEA', 'LAK', 16, 'PLANNED', TRUE),
('MYS', 'MY', 'Malaysia',    '',           'SEA', 'MYR', 17, 'PLANNED', TRUE);

-- 西亚（扩展波次 3，剔除高收入海湾发达国家）
INSERT INTO claw.countries (code, code_iso2, name_en, name_local, region, currency_code, pilot_order, status, data_residency) VALUES
('JOR', 'JO', 'Jordan',           '', 'WEST_ASIA', 'JOD', 21, 'PLANNED', TRUE),
('EGY', 'EG', 'Egypt',            '', 'WEST_ASIA', 'EGP', 22, 'PLANNED', TRUE),
('IRN', 'IR', 'Iran',             '', 'WEST_ASIA', 'IRR', 23, 'PLANNED', TRUE),
('IRQ', 'IQ', 'Iraq',             '', 'WEST_ASIA', 'IQD', 24, 'PLANNED', TRUE),
('LBN', 'LB', 'Lebanon',          '', 'WEST_ASIA', 'LBP', 25, 'PLANNED', TRUE),
('SYR', 'SY', 'Syria',            '', 'WEST_ASIA', 'SYP', 26, 'PLANNED', TRUE),
('YEM', 'YE', 'Yemen',            '', 'WEST_ASIA', 'YER', 27, 'PLANNED', TRUE),
('PSE', 'PS', 'Palestine',        '', 'WEST_ASIA', 'USD', 28, 'PLANNED', TRUE);

-- 非洲（扩展波次 4）
INSERT INTO claw.countries (code, code_iso2, name_en, name_local, region, currency_code, pilot_order, status, data_residency) VALUES
('NGA', 'NG', 'Nigeria',    '', 'AFRICA', 'NGN', 31, 'PLANNED', TRUE),
('KEN', 'KE', 'Kenya',      '', 'AFRICA', 'KES', 32, 'PLANNED', TRUE),
('ETH', 'ET', 'Ethiopia',   '', 'AFRICA', 'ETB', 33, 'PLANNED', TRUE),
('GHA', 'GH', 'Ghana',      '', 'AFRICA', 'GHS', 34, 'PLANNED', TRUE),
('TZA', 'TZ', 'Tanzania',   '', 'AFRICA', 'TZS', 35, 'PLANNED', TRUE),
('ZAF', 'ZA', 'South Africa','','AFRICA', 'ZAR', 36, 'PLANNED', TRUE),
('MAR', 'MA', 'Morocco',    '', 'AFRICA', 'MAD', 37, 'PLANNED', TRUE),
('DZA', 'DZ', 'Algeria',    '', 'AFRICA', 'DZD', 38, 'PLANNED', TRUE);

-- 东欧 / 俄罗斯（扩展波次 5）
INSERT INTO claw.countries (code, code_iso2, name_en, name_local, region, currency_code, pilot_order, status, data_residency) VALUES
('RUS', 'RU', 'Russia',     '', 'EAST_EUROPE', 'RUB', 41, 'PLANNED', TRUE),
('UKR', 'UA', 'Ukraine',    '', 'EAST_EUROPE', 'UAH', 42, 'PLANNED', TRUE),
('BLR', 'BY', 'Belarus',    '', 'EAST_EUROPE', 'BYN', 43, 'PLANNED', TRUE),
('KAZ', 'KZ', 'Kazakhstan', '', 'EAST_EUROPE', 'KZT', 44, 'PLANNED', TRUE),
('SRB', 'RS', 'Serbia',     '', 'EAST_EUROPE', 'RSD', 45, 'PLANNED', TRUE),
('GEO', 'GE', 'Georgia',    '', 'EAST_EUROPE', 'GEL', 46, 'PLANNED', TRUE),
('ARM', 'AM', 'Armenia',    '', 'EAST_EUROPE', 'AMD', 47, 'PLANNED', TRUE);

-- 排除国（EXCLUDED）：中国与发达国家暂不做
INSERT INTO claw.countries (code, code_iso2, name_en, region, currency_code, pilot_order, status, data_residency, regulatory_note) VALUES
('CHN', 'CN', 'China',                 'EXCLUDED', 'CNY', 0, 'EXCLUDED', FALSE, '明确排除：暂不进入中国与发达国家市场'),
('USA', 'US', 'United States',         'EXCLUDED', 'USD', 0, 'EXCLUDED', FALSE, '发达国家：暂不做'),
('GBR', 'GB', 'United Kingdom',        'EXCLUDED', 'GBP', 0, 'EXCLUDED', FALSE, '发达国家：暂不做'),
('DEU', 'DE', 'Germany',               'EXCLUDED', 'EUR', 0, 'EXCLUDED', FALSE, '发达国家：暂不做'),
('FRA', 'FR', 'France',                'EXCLUDED', 'EUR', 0, 'EXCLUDED', FALSE, '发达国家：暂不做'),
('JPN', 'JP', 'Japan',                 'EXCLUDED', 'JPY', 0, 'EXCLUDED', FALSE, '发达国家：暂不做'),
('KOR', 'KR', 'South Korea',           'EXCLUDED', 'KRW', 0, 'EXCLUDED', FALSE, '发达国家：暂不做'),
('AUS', 'AU', 'Australia',             'EXCLUDED', 'AUD', 0, 'EXCLUDED', FALSE, '发达国家：暂不做'),
('CAN', 'CA', 'Canada',                'EXCLUDED', 'CAD', 0, 'EXCLUDED', FALSE, '发达国家：暂不做'),
('SGP', 'SG', 'Singapore',             'EXCLUDED', 'SGD', 0, 'EXCLUDED', FALSE, '发达城市国家：暂不做'),
('ARE', 'AE', 'United Arab Emirates',  'EXCLUDED', 'AED', 0, 'EXCLUDED', FALSE, '高收入海湾国家：暂不做'),
('QAT', 'QA', 'Qatar',                 'EXCLUDED', 'QAR', 0, 'EXCLUDED', FALSE, '高收入海湾国家：暂不做'),
('SAU', 'SA', 'Saudi Arabia',          'EXCLUDED', 'SAR', 0, 'EXCLUDED', FALSE, '高收入海湾国家：暂不做');

-- ---------------------------------------------------------------------
-- 2. 身份提供方注册表（IdP 适配器）：每国一份，OAuth2.0 通用适配
--    进新国家只需插入一行 + 配置 config_json，无需改核心代码。
-- ---------------------------------------------------------------------
CREATE TABLE claw.identity_providers (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    country_code  VARCHAR(3)   NOT NULL REFERENCES claw.countries (code),
    provider_code VARCHAR(32)  NOT NULL,                -- CAMDIGIKEY 等
    protocol      VARCHAR(16)  NOT NULL DEFAULT 'OAUTH2',
    display_name  VARCHAR(64),
    config_json   JSONB,                                -- token_url / introspect_url / scopes
    priority      INT          NOT NULL DEFAULT 1,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (country_code, provider_code)
);

-- 柬埔寨：CamDigiKey（MPTC 国家数字身份）
INSERT INTO claw.identity_providers (country_code, provider_code, protocol, display_name, config_json, priority)
VALUES ('KHM', 'CAMDIGIKEY', 'OAUTH2', 'CamDigiKey (MPTC)',
        '{"authorize_url":"https://id.mptc.gov.kh/authorize","token_url":"https://id.mptc.gov.kh/token","introspect_url":"https://id.mptc.gov.kh/introspect","scopes":["name","dob","national_id","photo"]}',
        1);

-- ---------------------------------------------------------------------
-- 3. 支付提供方注册表（支付清算适配器）：通道策略按国家挂载
-- ---------------------------------------------------------------------
CREATE TABLE claw.payment_providers (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    country_code  VARCHAR(3)   NOT NULL REFERENCES claw.countries (code),
    provider_code VARCHAR(32)  NOT NULL,                -- ABA / KHQR / BAKONG / WING / CASH
    provider_type VARCHAR(16)  NOT NULL,                -- BANK / QR / CLEARING / CASH
    config_json   JSONB,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (country_code, provider_code)
);

-- 柬埔寨：零牌照资金路径（KHQR 收单 + ABA 受托 + Bakong 清算）
INSERT INTO claw.payment_providers (country_code, provider_code, provider_type, config_json) VALUES
('KHM', 'ABA',    'BANK',     '{"role":"escrow_custodian","settlement":"escrow"}'),
('KHM', 'KHQR',   'QR',       '{"scheme":"national_qr","acquirer":"aba"}'),
('KHM', 'BAKONG','CLEARING', '{"retail":"bakong","national_clearing":true}'),
('KHM', 'WING',   'BANK',     '{"role":"agent_network"}'),
('KHM', 'CASH',   'CASH',     '{"role":"fallback"}');

-- ---------------------------------------------------------------------
-- 4. 监管牌照注册表（合规适配器）：每国必牌照/已获/申请中
-- ---------------------------------------------------------------------
CREATE TABLE claw.regulatory_licenses (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    country_code  VARCHAR(3)   NOT NULL REFERENCES claw.countries (code),
    license_type  VARCHAR(32)  NOT NULL,                -- ELECTRIC_UTILITY / CONSUMER_CREDIT 等
    authority     VARCHAR(64),
    status        VARCHAR(16)  NOT NULL,                -- OBTAINED / APPLYING / REQUIRED / NOT_REQUIRED
    required      BOOLEAN      NOT NULL DEFAULT TRUE,
    notes         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (country_code, license_type)
);

-- 柬埔寨：电力局充电设施运营牌照（红利，申请中）+ 消费信贷（经 RTO 规避，REQUIRED 标注）
INSERT INTO claw.regulatory_licenses (country_code, license_type, authority, status, required, notes) VALUES
('KHM', 'ELECTRIC_UTILITY', 'Electricite du Cambodge (EDC)', 'APPLYING', TRUE,
       '充电设施运营牌照红利：低电价/免变压器/免押金/免接入费，金边调研行程内申报'),
('KHM', 'CONSUMER_CREDIT',  'National Bank of Cambodia (NBC)', 'REQUIRED', TRUE,
       '经「使用权租金+期满买断(RTO)」及联合持牌租赁公司规避，不单独申牌'),
('KHM', 'ABA_ESCROW',       'ABA Bank', 'OBTAINED', TRUE,
       '三专户(残值准备金/电池基金/车辆风险准备金)以 escrow 受托口径开立');

-- ---------------------------------------------------------------------
-- 5. 运营主体（治理层）：每国一个运营实体，区分直营/特许经营/合资
--    id=1 固定为柬埔寨自有试点，对齐 V1/V2 既有行 tenant_id 默认值 1
-- ---------------------------------------------------------------------
CREATE TABLE claw.tenants (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    country_code  VARCHAR(3)   NOT NULL REFERENCES claw.countries (code),
    name          VARCHAR(128) NOT NULL,
    operator_type VARCHAR(16)  NOT NULL,                -- OWNED / FRANCHISE / JV
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- tenants.id 为 GENERATED ALWAYS AS IDENTITY，种子需显式指定 id 时必须用 OVERRIDING SYSTEM VALUE
INSERT INTO claw.tenants (id, country_code, name, operator_type, status)
OVERRIDING SYSTEM VALUE
VALUES (1, 'KHM', 'Claw Cambodia (Pilot)', 'OWNED', 'ACTIVE');

-- ---------------------------------------------------------------------
-- 6. 合作伙伴分润规则（治理层 · 分润引擎雏形）
--    共营本质：本地合作方自带银行/身份局/监管关系，平台收 SaaS+分润
-- ---------------------------------------------------------------------
CREATE TABLE claw.partner_programs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    country_code  VARCHAR(3)   NOT NULL REFERENCES claw.countries (code),
    partner_type  VARCHAR(24)  NOT NULL,                -- BANK / IDENTITY / ENERGY_REGULATOR / FLEET / RESELLER
    share_basis   VARCHAR(24)  NOT NULL,                -- GMV / PROFIT / SUBSCRIPTION
    share_pct     NUMERIC(5,2) NOT NULL DEFAULT 0,
    currency      VARCHAR(3),
    notes         TEXT,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 柬埔寨分润雏形（占位，按实地谈判校准）
INSERT INTO claw.partner_programs (country_code, partner_type, share_basis, share_pct, notes) VALUES
('KHM', 'IDENTITY',          'SUBSCRIPTION', 0.00, 'CamDigiKey 国家基础设施，零边际成本'),
('KHM', 'ENERGY_REGULATOR',  'PROFIT',       0.00, 'EDC 牌照红利体现为低电价而非直接分润'),
('KHM', 'BANK',              'GMV',          0.30, 'ABA 受托+清算通道费（escrow 资金路径）'),
('KHM', 'FLEET',             'GMV',          0.05, 'PassApp 等车队/嘟嘟车入口导流分润'),
('KHM', 'RESELLER',          'GMV',          0.10, '本地加盟/渠道分销分润');

CREATE INDEX idx_identity_providers_country ON claw.identity_providers (country_code, is_active);
CREATE INDEX idx_payment_providers_country ON claw.payment_providers (country_code, is_active);
CREATE INDEX idx_regulatory_licenses_country ON claw.regulatory_licenses (country_code);
CREATE INDEX idx_tenants_country ON claw.tenants (country_code);
CREATE INDEX idx_partner_programs_country ON claw.partner_programs (country_code, is_active);
