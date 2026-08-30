-- =====================================================================
-- Claw 平台 V60 增量（入驻管理 · 增量 C 第二批）
-- 依据：增量设计-入驻管理.md §2.2
-- 范围（3 张新表 + 5 张既有表改造）：
--   · onboarding_deposit_tiers   保证金档位（Q1 三档 / Q2b 倍率可配）
--   · onboarding_deposits        保证金缴纳台账（业务台账，不入复式记账）
--   · onboarding_credit_blocks   额度超限阻断记录（风控留痕）
--   · inventory                  补货值列 unit_value / value_currency / unit_value_source
--   · stations / manufacturers / merchants  入驻治理字段
--   · merchants.affiliate_station_id        可选归属站（取代 stations.merchant_id，见 §1.2 裁定）
--   · principal_bindings        principal_type 扩展支持 MERCHANT
--
-- 全量幂等。stations.merchant_id 的删除按「先建列 → 回填 → 校验后删列」三步走，
-- 且删除前做覆盖率自检：影响面 ≠ 回填数 时不删列并直接报错（防数据丢失）。
-- =====================================================================

SET search_path = claw;

-- ---------- (6) onboarding_deposit_tiers — 保证金档位（Q1/Q2/Q2b 落地表） ----------
CREATE TABLE IF NOT EXISTS onboarding_deposit_tiers (
    id                     BIGSERIAL PRIMARY KEY,
    applicant_type         VARCHAR(20)     NOT NULL,
    tier_code              VARCHAR(40)     NOT NULL,          -- BASIC / STANDARD / PREMIUM
    tier_name              VARCHAR(80)     NOT NULL,
    deposit_amount         NUMERIC(16,2)   NOT NULL,          -- 保证金金额
    credit_multiplier      NUMERIC(8,4)    NOT NULL DEFAULT 3, -- 授信倍率（可配，非硬编码）
    credit_limit_override  NUMERIC(16,2),                      -- 绝对额度覆盖值；NULL 时按 deposit × multiplier
    credit_type            VARCHAR(24)     NOT NULL DEFAULT 'CONSIGNMENT_VALUE',
    currency               VARCHAR(8)      NOT NULL DEFAULT 'USD',
    benefit_desc           TEXT,
    sort_no                INT             NOT NULL DEFAULT 0,
    enabled                BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_by             BIGINT REFERENCES users (id),
    UNIQUE (applicant_type, tier_code)
);

-- 种子：Q1 拍板 3 档（5,000 / 20,000 / 50,000），Q2b 授信额度 = 保证金 × 3
-- 三类主体各一套（Q6 推荐默认），数值相同；倍率落在列上，公司可逐档调整。
INSERT INTO onboarding_deposit_tiers
    (applicant_type, tier_code, tier_name, deposit_amount, credit_multiplier, credit_limit_override,
     credit_type, currency, benefit_desc, sort_no, enabled)
VALUES
    ('STATION', 'BASIC',    '第一档',  5000.00,  3.0000, NULL, 'CONSIGNMENT_VALUE', 'USD', '基础档：寄售设备名义货值上限 15,000 USD，适合单点起步', 10, TRUE),
    ('STATION', 'STANDARD', '第二档', 20000.00,  3.0000, NULL, 'CONSIGNMENT_VALUE', 'USD', '标准档：寄售设备名义货值上限 60,000 USD，可覆盖常规铺货需求', 20, TRUE),
    ('STATION', 'PREMIUM',  '第三档', 50000.00,  3.0000, NULL, 'CONSIGNMENT_VALUE', 'USD', '旗舰档：寄售设备名义货值上限 150,000 USD，优先获得新品铺货与活动资源', 30, TRUE),
    ('MANUFACTURER', 'BASIC',    '第一档',  5000.00, 3.0000, NULL, 'CONSIGNMENT_VALUE', 'USD', '基础档：可铺出寄售设备名义货值上限 15,000 USD', 10, TRUE),
    ('MANUFACTURER', 'STANDARD', '第二档', 20000.00, 3.0000, NULL, 'CONSIGNMENT_VALUE', 'USD', '标准档：可铺出寄售设备名义货值上限 60,000 USD', 20, TRUE),
    ('MANUFACTURER', 'PREMIUM',  '第三档', 50000.00, 3.0000, NULL, 'CONSIGNMENT_VALUE', 'USD', '旗舰档：可铺出寄售设备名义货值上限 150,000 USD', 30, TRUE),
    ('MERCHANT', 'BASIC',    '第一档',  5000.00, 3.0000, NULL, 'CONSIGNMENT_VALUE', 'USD', '基础档：可持有寄售设备名义货值上限 15,000 USD', 10, TRUE),
    ('MERCHANT', 'STANDARD', '第二档', 20000.00, 3.0000, NULL, 'CONSIGNMENT_VALUE', 'USD', '标准档：可持有寄售设备名义货值上限 60,000 USD', 20, TRUE),
    ('MERCHANT', 'PREMIUM',  '第三档', 50000.00, 3.0000, NULL, 'CONSIGNMENT_VALUE', 'USD', '旗舰档：可持有寄售设备名义货值上限 150,000 USD', 30, TRUE)
ON CONFLICT (applicant_type, tier_code) DO NOTHING;

-- ---------- (7) onboarding_deposits — 保证金缴纳台账（业务台账，不入复式记账） ----------
CREATE TABLE IF NOT EXISTS onboarding_deposits (
    id                BIGSERIAL PRIMARY KEY,
    deposit_no        VARCHAR(40)    NOT NULL,                -- DEP{yyyyMMdd}{4位序号}
    application_id    BIGINT         NOT NULL REFERENCES onboarding_applications (id),
    principal_type    VARCHAR(20)    NOT NULL,                -- 冗余，激活后按组织查
    principal_id      BIGINT,                                 -- 激活后回填
    tier_id           BIGINT REFERENCES onboarding_deposit_tiers (id),
    amount            NUMERIC(16,2)  NOT NULL,
    currency          VARCHAR(8)     NOT NULL DEFAULT 'USD',
    pay_method        VARCHAR(24)    NOT NULL DEFAULT 'OFFLINE_TRANSFER',
    voucher_url       VARCHAR(500),
    payer_name        VARCHAR(120),
    payer_account     VARCHAR(120),
    status            VARCHAR(16)    NOT NULL DEFAULT 'PENDING_CONFIRM', -- PENDING_CONFIRM/CONFIRMED/REJECTED/REFUNDED
    confirmed_by      BIGINT REFERENCES users (id),
    confirmed_at      TIMESTAMPTZ,
    reject_reason     VARCHAR(255),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_onb_dep_no      ON onboarding_deposits (deposit_no);
CREATE INDEX        IF NOT EXISTS idx_onb_dep_app       ON onboarding_deposits (application_id);
CREATE INDEX        IF NOT EXISTS idx_onb_dep_principal ON onboarding_deposits (principal_type, principal_id);

-- ---------- (8) onboarding_credit_blocks — 额度超限阻断记录 ----------
CREATE TABLE IF NOT EXISTS onboarding_credit_blocks (
    id              BIGSERIAL PRIMARY KEY,
    block_no        VARCHAR(40)    NOT NULL,                  -- CRB{yyyyMMdd}{4位序号}
    principal_type  VARCHAR(20)    NOT NULL,                  -- 被限主体（首期仅 STATION）
    principal_id    BIGINT         NOT NULL,
    scene           VARCHAR(32)    NOT NULL,                  -- CONSIGN_SHIP / TRANSFER_IN / FULFILL_SHIP
    biz_ref_type    VARCHAR(32),                              -- TRANSFER / FULFILLMENT / CONSIGNMENT
    biz_ref_id      BIGINT,
    device_count    INT            NOT NULL,
    incoming_value  NUMERIC(16,2)  NOT NULL,
    used_value      NUMERIC(16,2)  NOT NULL,
    credit_limit    NUMERIC(16,2)  NOT NULL,
    overflow_value  NUMERIC(16,2)  NOT NULL,
    operator_id     BIGINT,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_crb_principal
    ON onboarding_credit_blocks (principal_type, principal_id, created_at DESC);

-- ---------- (9) inventory — 补货值列（PRD 遗漏项，额度校验的前提） ----------
-- 商品价格在 product_skus（SKU 级），一个 product 多个 SKU，查询时现算既歧义又会因调价漂移。
-- 故在 inventory 落「入站时点货值快照」，入站写入后不再变动。
ALTER TABLE inventory ADD COLUMN IF NOT EXISTS unit_value        NUMERIC(16,2);
ALTER TABLE inventory ADD COLUMN IF NOT EXISTS value_currency    VARCHAR(8) NOT NULL DEFAULT 'USD';
ALTER TABLE inventory ADD COLUMN IF NOT EXISTS unit_value_source VARCHAR(24);

-- 存量回填：取该 product 下价格最低的在售 SKU 价（保守口径，可按需调整）
UPDATE inventory i
   SET unit_value        = sub.price,
       unit_value_source = 'BACKFILL'
  FROM (SELECT product_id, min(price) AS price
          FROM product_skus
         WHERE status = 'ACTIVE' AND deleted = FALSE
         GROUP BY product_id) sub
 WHERE i.product_id = sub.product_id
   AND i.unit_value IS NULL;

-- 额度校验专用部分索引（只索引参与额度计算的在途 + 在库寄售行）
CREATE INDEX IF NOT EXISTS idx_inv_credit_scope
    ON inventory (holder_station_id)
 WHERE ownership_type = 'CONSIGNED'
   AND current_status IN ('IN_TRANSIT', 'AT_STATION');

-- ---------- (10a) stations — 入驻治理字段 + 站点展示字段 ----------
ALTER TABLE stations ADD COLUMN IF NOT EXISTS onboarding_status          VARCHAR(24) DEFAULT 'PENDING';
ALTER TABLE stations ADD COLUMN IF NOT EXISTS onboarding_application_id  BIGINT;
ALTER TABLE stations ADD COLUMN IF NOT EXISTS deposit_tier_id            BIGINT;
ALTER TABLE stations ADD COLUMN IF NOT EXISTS credit_limit               NUMERIC(16,2);
ALTER TABLE stations ADD COLUMN IF NOT EXISTS disabled_at                TIMESTAMPTZ;
ALTER TABLE stations ADD COLUMN IF NOT EXISTS disabled_by                BIGINT;
ALTER TABLE stations ADD COLUMN IF NOT EXISTS disabled_reason            TEXT;
ALTER TABLE stations ADD COLUMN IF NOT EXISTS signboard_url              VARCHAR(500);
ALTER TABLE stations ADD COLUMN IF NOT EXISTS geo_address                TEXT;

-- ---------- (10b) manufacturers — 入驻治理字段 ----------
ALTER TABLE manufacturers ADD COLUMN IF NOT EXISTS onboarding_status          VARCHAR(24) DEFAULT 'PENDING';
ALTER TABLE manufacturers ADD COLUMN IF NOT EXISTS onboarding_application_id  BIGINT;
ALTER TABLE manufacturers ADD COLUMN IF NOT EXISTS deposit_tier_id            BIGINT;
ALTER TABLE manufacturers ADD COLUMN IF NOT EXISTS credit_limit               NUMERIC(16,2);
ALTER TABLE manufacturers ADD COLUMN IF NOT EXISTS disabled_at                TIMESTAMPTZ;
ALTER TABLE manufacturers ADD COLUMN IF NOT EXISTS disabled_by                BIGINT;
ALTER TABLE manufacturers ADD COLUMN IF NOT EXISTS disabled_reason            TEXT;

-- ---------- (10c) merchants — 入驻治理字段 + 可选归属站 affiliate_station_id ----------
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS onboarding_status          VARCHAR(24) DEFAULT 'PENDING';
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS onboarding_application_id  BIGINT;
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS deposit_tier_id            BIGINT;
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS credit_limit               NUMERIC(16,2);
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS disabled_at                TIMESTAMPTZ;
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS disabled_by                BIGINT;
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS disabled_reason            TEXT;
-- §1.2 裁定：商家是独立主体，外键方向必须是 N merchants → 1 station。
-- 语义：可选的「经营所在地服务站」，用于「附近商家」检索与未来铺位招商落位；
--       不表示任何归属或挂靠关系，商家账号 / 保证金 / 授信额度全部独立。允许为 NULL。
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS affiliate_station_id BIGINT REFERENCES stations (id);
CREATE INDEX IF NOT EXISTS idx_merchant_affiliate ON merchants (affiliate_station_id);

-- ---------- 历史主体回填 onboarding_status ----------
-- 存量主体（V1–V58 已存在、未走入驻流程）一律视为已激活，避免新字段把既有业务卡死
-- （与设计 Q18「不能因存量数据缺失而阻断既有业务」同一精神）。
-- 这是一次性回填：本迁移执行时，三张表里的行全部是历史数据。
UPDATE stations      SET onboarding_status = 'ACTIVE' WHERE onboarding_status IS NULL OR onboarding_status = 'PENDING';
UPDATE manufacturers SET onboarding_status = 'ACTIVE' WHERE onboarding_status IS NULL OR onboarding_status = 'PENDING';
-- merchants 已有 status（PENDING/ACTIVE/REJECTED）：以其为准同步，避免把已审核的历史行误置为 ACTIVE。
-- 限定只处理 onboarding_status='PENDING' 的行，保证重复执行时不会把后续被禁用（DISABLED）的行打回。
UPDATE merchants SET onboarding_status = CASE
    WHEN status = 'ACTIVE'   THEN 'ACTIVE'
    WHEN status = 'REJECTED' THEN 'REJECTED'
    ELSE 'PENDING'
END
 WHERE onboarding_status = 'PENDING';

-- ---------- (11) stations.merchant_id 处置：先回填 → 覆盖率自检 → 再删列 ----------
-- 步骤 1（建列）已在 10c 完成；步骤 2 回填：把 V52 遗留的「站挂商」关系反向搬到「商属站」。
UPDATE merchants m
   SET affiliate_station_id = s.id
  FROM stations s
 WHERE s.merchant_id = m.id
   AND m.affiliate_station_id IS NULL;

-- 步骤 3 删除旧列，但先做覆盖率自检：
--   A. 影响面：SELECT count(*) FROM claw.stations WHERE merchant_id IS NOT NULL;
--   B. 回填数：SELECT count(*) FROM claw.merchants WHERE affiliate_station_id IS NOT NULL;
-- 二者不相等说明存在「多站指向同一商家」等数据异常，此时禁止删列并直接报错，防止数据丢失。
DO $$
DECLARE
    v_stations_with_merchant BIGINT := 0;
    v_merchants_backfilled   BIGINT := 0;
    v_column_exists          BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'claw' AND table_name = 'stations' AND column_name = 'merchant_id'
    ) INTO v_column_exists;

    IF v_column_exists THEN
        EXECUTE 'SELECT count(*) FROM claw.stations WHERE merchant_id IS NOT NULL'
            INTO v_stations_with_merchant;
        EXECUTE 'SELECT count(*) FROM claw.merchants WHERE affiliate_station_id IS NOT NULL'
            INTO v_merchants_backfilled;

        IF v_stations_with_merchant = v_merchants_backfilled THEN
            EXECUTE 'ALTER TABLE claw.stations DROP COLUMN merchant_id';
            RAISE NOTICE 'V60: stations.merchant_id 已废弃并删除（影响面=% 条，回填=% 条，一致）',
                v_stations_with_merchant, v_merchants_backfilled;
        ELSE
            RAISE EXCEPTION
                'V60 拒绝删除 stations.merchant_id：影响面=% 条，但 merchants.affiliate_station_id 回填仅 % 条，覆盖率不一致，请先人工核对（执行前必须跑设计 §1.2 的两条探查 SQL）',
                v_stations_with_merchant, v_merchants_backfilled;
        END IF;
    ELSE
        RAISE NOTICE 'V60: stations.merchant_id 已不存在，跳过删除（幂等）';
    END IF;
END $$;

-- ---------- (11b) principal_bindings — principal_type 扩展支持 MERCHANT ----------
-- V47 建表未加 CHECK 约束，但为防御历史差异，用 DO 块按列名查找并重建任意 CHECK 约束。
DO $$
DECLARE c record;
BEGIN
    FOR c IN
        SELECT con.conname
          FROM pg_constraint con
          JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
         WHERE con.conrelid = 'claw.principal_bindings'::regclass
           AND con.contype  = 'c'
           AND att.attname  = 'principal_type'
    LOOP
        EXECUTE format('ALTER TABLE claw.principal_bindings DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE principal_bindings
    ADD CONSTRAINT principal_bindings_principal_type_check
    CHECK (principal_type IN ('MANUFACTURER','STATION','MERCHANT'));

-- ---------- 跨表外键补齐（V59 的 deposit_tier_id / 三张主体表的档位列） ----------
-- PG 不支持 ADD CONSTRAINT IF NOT EXISTS，故用 DO 块按约束名判断，保证幂等。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_onb_app_deposit_tier') THEN
        ALTER TABLE claw.onboarding_applications
            ADD CONSTRAINT fk_onb_app_deposit_tier
            FOREIGN KEY (deposit_tier_id) REFERENCES claw.onboarding_deposit_tiers (id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_stations_deposit_tier') THEN
        ALTER TABLE claw.stations
            ADD CONSTRAINT fk_stations_deposit_tier
            FOREIGN KEY (deposit_tier_id) REFERENCES claw.onboarding_deposit_tiers (id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_manufacturers_deposit_tier') THEN
        ALTER TABLE claw.manufacturers
            ADD CONSTRAINT fk_manufacturers_deposit_tier
            FOREIGN KEY (deposit_tier_id) REFERENCES claw.onboarding_deposit_tiers (id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_merchants_deposit_tier') THEN
        ALTER TABLE claw.merchants
            ADD CONSTRAINT fk_merchants_deposit_tier
            FOREIGN KEY (deposit_tier_id) REFERENCES claw.onboarding_deposit_tiers (id);
    END IF;
END $$;
