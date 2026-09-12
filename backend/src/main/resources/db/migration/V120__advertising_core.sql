-- ============================================================================
-- V120 广告子系统核心表（8 张 ad_* 表，schema=claw）
-- 仅新建，绝不修改 V1–V119。广告计费复用既有
--   RevenueTriggerEvent(USAGE) → FeeRule → revenue_split_rules → ledger 管道，不另造计费。
-- 多值字段（capabilities / targeting）以 CSV / jsonb 存储，规避 text[] 数组映射依赖。
-- ============================================================================

CREATE TABLE claw.ad_accounts (
    id          BIGSERIAL      PRIMARY KEY,
    owner_type  VARCHAR(16)    NOT NULL,            -- MERCHANT / USER
    owner_id    BIGINT         NOT NULL,
    balance     NUMERIC(18,4)   NOT NULL DEFAULT 0,
    status      VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE TABLE claw.ad_creatives (
    id              BIGSERIAL      PRIMARY KEY,
    account_id      BIGINT         NOT NULL,
    type            VARCHAR(16)    NOT NULL,         -- IMAGE / VIDEO
    file_url        TEXT           NOT NULL,
    mime            VARCHAR(64),
    duration_sec    INT,
    thumb_url       TEXT,
    width           INT,
    height          INT,
    white_bg        BOOLEAN        NOT NULL DEFAULT FALSE,
    source          VARCHAR(16)    NOT NULL DEFAULT 'UPLOAD', -- UPLOAD / FROM_PRODUCT
    product_ref     VARCHAR(64),
    editable_json   JSONB,                             -- 标题/文案/CTA/裁剪预设
    created_at      TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE TABLE claw.ad_campaigns (
    id              BIGSERIAL      PRIMARY KEY,
    account_id      BIGINT         NOT NULL,
    name            VARCHAR(128)   NOT NULL,
    status          VARCHAR(16)    NOT NULL DEFAULT 'DRAFT', -- DRAFT/ACTIVE/PAUSED/ENDED
    budget          NUMERIC(18,4)   NOT NULL DEFAULT 0,
    bid_mode        VARCHAR(16)    NOT NULL,           -- CPM / CPC / FLAT_PLAY / FLAT_TIME
    bid_price       NUMERIC(18,4)   NOT NULL DEFAULT 0,
    time_slots      JSONB,                             -- 时段配置
    regions         JSONB,                             -- geofence 区域
    targeting_json  JSONB,                             -- asset_types/capabilities/scenarios
    start_at        TIMESTAMP,
    end_at          TIMESTAMP,
    spent           NUMERIC(18,4)   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE TABLE claw.ad_campaign_creatives (
    id              BIGSERIAL      PRIMARY KEY,
    campaign_id     BIGINT         NOT NULL,
    creative_id     BIGINT         NOT NULL,
    weight          INT            NOT NULL DEFAULT 1,
    UNIQUE (campaign_id, creative_id)
);

CREATE TABLE claw.ad_screens (
    id              BIGSERIAL      PRIMARY KEY,
    terminal_type   VARCHAR(16)    NOT NULL,           -- ASSET / MOBILE
    device_id       BIGINT,                            -- 资产屏设备；手机 app 为 NULL
    asset_id        BIGINT,                            -- 资产屏关联资产
    screen_type     VARCHAR(16)    NOT NULL DEFAULT 'SCREEN', -- BODY / SCREEN / MOBILE
    geofence        JSONB,                             -- 屏幕位置 geofence
    capabilities    TEXT,                              -- CSV：该屏能力标签
    owner_id        BIGINT,
    status          VARCHAR(16)    NOT NULL DEFAULT 'ONLINE',
    created_at      TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE TABLE claw.ad_play_logs (
    id              BIGSERIAL      PRIMARY KEY,
    campaign_id     BIGINT         NOT NULL,
    creative_id     BIGINT         NOT NULL,
    screen_id       BIGINT         NOT NULL,
    played_at       TIMESTAMP      NOT NULL DEFAULT now(),
    duration_ms     BIGINT,
    play_count      INT            NOT NULL DEFAULT 1,
    click_count     INT            NOT NULL DEFAULT 0,
    charge_mode     VARCHAR(16),                      -- 计费模式（同 bid_mode 取值）
    amount          NUMERIC(18,4)   NOT NULL DEFAULT 0,
    settled         BOOLEAN        NOT NULL DEFAULT FALSE
);

CREATE TABLE claw.ad_match_queue (
    id              BIGSERIAL      PRIMARY KEY,
    screen_id       BIGINT         NOT NULL,
    campaign_id     BIGINT         NOT NULL,
    rank            INT            NOT NULL DEFAULT 0,
    pinned          BOOLEAN        NOT NULL DEFAULT FALSE,
    next_at         TIMESTAMP,
    UNIQUE (screen_id, campaign_id)
);

CREATE TABLE claw.ad_auction_log (
    id                  BIGSERIAL      PRIMARY KEY,
    screen_id           BIGINT         NOT NULL,
    round_at            TIMESTAMP      NOT NULL DEFAULT now(),
    winner_campaign_id  BIGINT,
    bid                 NUMERIC(18,4),
    runner_up           BIGINT
);

CREATE INDEX idx_ad_creatives_account   ON claw.ad_creatives (account_id);
CREATE INDEX idx_ad_campaigns_account   ON claw.ad_campaigns (account_id);
CREATE INDEX idx_ad_campaigns_status    ON claw.ad_campaigns (status);
CREATE INDEX idx_ad_screens_terminal    ON claw.ad_screens (terminal_type);
CREATE INDEX idx_ad_screens_asset       ON claw.ad_screens (asset_id);
CREATE INDEX idx_ad_play_logs_campaign  ON claw.ad_play_logs (campaign_id);
CREATE INDEX idx_ad_play_logs_screen    ON claw.ad_play_logs (screen_id);
CREATE INDEX idx_ad_match_queue_screen  ON claw.ad_match_queue (screen_id);
