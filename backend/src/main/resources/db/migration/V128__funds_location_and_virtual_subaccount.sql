-- ============================================================================
-- V128 资金路由与清分 · L2 托管资金层（schema=claw）
-- 仅新建 2 张表，绝不修改 V1–V127。
--
-- ① funds_location  托管点位：显式回答「每笔钱在哪家机构的哪个账户」，
--                   破产隔离在 location_type 上显式建模。
-- ② virtual_subaccount 虚拟子户：用户/商家不占真实银行账户，只在托管点位下开逻辑子户；
--                   承载「虚拟子户 ↔ 账本用户(owner_user_id)」映射。
--
-- 表名不加 claw. 前缀（与 V87/V126/V127 一致）；全量幂等（IF NOT EXISTS）。
-- ============================================================================

SET search_path = claw;

-- ① 托管点位（账本账户 ↔ 真实托管/备付金账户）
CREATE TABLE IF NOT EXISTS funds_location (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    location_code        VARCHAR(48)  NOT NULL UNIQUE,   -- ABA_PAYWAY_RESERVE / BANK_ESCROW_01 / MPTC_DIRECT
    institution          VARCHAR(64)  NOT NULL,          -- ABA / BAKONG / BANK_X
    institution_acct_no  VARCHAR(64),                    -- 脱敏存（建议仅后 4 位 + 指纹）
    location_type        VARCHAR(24)  NOT NULL,          -- PLATFORM_OWN / CLIENT_CUSTODY / MERCHANT_DIRECT
    channel              VARCHAR(32),                    -- ABA_PAYWAY / BAKONG / BANK
    currency             CHAR(3)      NOT NULL DEFAULT 'USD',
    covers_account_types VARCHAR(256),                   -- 覆盖账本科目（逗号分隔，审计用）
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    last_reconciled_at   TIMESTAMPTZ,
    tenant_id            BIGINT       NOT NULL DEFAULT 1,
    deleted              BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fl_type ON funds_location (location_type, currency, status);

-- ② 虚拟子户（用户/商家不占真实银行账户）
CREATE TABLE IF NOT EXISTS virtual_subaccount (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vsa_no            VARCHAR(48) NOT NULL UNIQUE,        -- 平台生成，机构侧映射
    owner_type        VARCHAR(24) NOT NULL,               -- USER/STATION/MANUFACTURER/INVESTOR/INSURER/LOGISTICS
    owner_id          BIGINT      NOT NULL,
    owner_user_id     BIGINT      REFERENCES users (id),  -- 映射到的账本用户（平台内部方为 NULL）
    funds_location_id BIGINT      NOT NULL REFERENCES funds_location (id),
    currency          CHAR(3)     NOT NULL DEFAULT 'USD',
    external_sub_no   VARCHAR(64),                        -- 机构侧子户号 —— 待通道确认是否存在
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/FROZEN/CLOSED
    -- 预留（自持牌照规划，本期只留痕不启用）：
    kyc_level         VARCHAR(16),                        -- NONE/BASIC/ENHANCED
    daily_limit       NUMERIC(16,2),                      -- 日限额
    tenant_id         BIGINT      NOT NULL DEFAULT 1,
    deleted           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_vsa_owner
    ON virtual_subaccount (owner_type, owner_id, currency, funds_location_id)
    WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_vsa_owner ON virtual_subaccount (owner_type, owner_id);
