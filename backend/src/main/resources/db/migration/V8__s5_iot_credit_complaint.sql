-- =====================================================================
-- Claw 平台 V8 增量表（S5：IoT 遥测/轨迹 + Claw Score 信用分 + 投诉渠道）
-- 依据：《技术开发文档 v0.4》IoT 域/信用与风控域/合规域 + API 清单
--   · IoT：devices 设备、telemetry_latest 最新遥测、tracks 轨迹（时序）
--   · Claw Score：credit_scores（0-1000）+ credit_score_events（变更审计）
--   · 投诉：financial_consumer_complaints（平台/金融消费者中心/NBC 热线）
-- 通用规范继承 V1：schema claw、tenant_id、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 设备（domain.iot）
-- ---------------------------------------------------------------------
CREATE TABLE claw.devices (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id       BIGINT      NOT NULL REFERENCES claw.assets (id),
    device_type    VARCHAR(16) NOT NULL,  -- VEHICLE_TCU | BATTERY_BMS | CHARGER | CABINET | AD_SCREEN | CAMERA
    imei           VARCHAR(64) UNIQUE,
    protocol_ver   VARCHAR(16),
    last_online_at TIMESTAMPTZ,
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_id      BIGINT      NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_devices_asset ON claw.devices (asset_id);

-- ---------------------------------------------------------------------
-- 2. 最新遥测（domain.iot，Redis 热数据 + PG 落库）
-- ---------------------------------------------------------------------
CREATE TABLE claw.telemetry_latest (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id   BIGINT      NOT NULL REFERENCES claw.devices (id),
    asset_id    BIGINT      NOT NULL REFERENCES claw.assets (id),
    speed       NUMERIC(8,2),      -- km/h
    soc         NUMERIC(5,2),      -- 电量 %
    temp        NUMERIC(5,2),      -- 温度 ℃
    humid       NUMERIC(5,2),      -- 湿度 %
    faults      JSONB,             -- 故障码列表
    lat         NUMERIC(10,6),
    lng         NUMERIC(10,6),
    reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_id   BIGINT      NOT NULL DEFAULT 1,
    UNIQUE (device_id)
);

-- ---------------------------------------------------------------------
-- 3. 轨迹（domain.iot，时序；生产用 TimescaleDB hypertable 自动分区压缩）
-- ---------------------------------------------------------------------
CREATE TABLE claw.tracks (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id BIGINT      NOT NULL REFERENCES claw.devices (id),
    asset_id  BIGINT      NOT NULL REFERENCES claw.assets (id),
    ts        TIMESTAMPTZ NOT NULL,
    lat       NUMERIC(10,6),
    lng       NUMERIC(10,6),
    speed     NUMERIC(8,2),
    soc       NUMERIC(5,2)
);

CREATE INDEX idx_tracks_asset_ts  ON claw.tracks (asset_id, ts DESC);
CREATE INDEX idx_tracks_device_ts ON claw.tracks (device_id, ts DESC);

-- ---------------------------------------------------------------------
-- 4. Claw Score 信用分（domain.credit，0-1000）
--    因子：换电频次 30% | 准时付费 30% | 里程出车 20% | 好评率 10% | 收入稳定 10%
-- ---------------------------------------------------------------------
CREATE TABLE claw.credit_scores (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES claw.users (id),
    score      INT         NOT NULL DEFAULT 600 CHECK (score >= 0 AND score <= 1000),
    factors    JSONB       NOT NULL DEFAULT '{}',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id)
);

-- 信用分变更事件（可审计）
CREATE TABLE claw.credit_score_events (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES claw.users (id),
    delta      INT         NOT NULL,
    reason     VARCHAR(64) NOT NULL,
    ref_id     VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_credit_events_user ON claw.credit_score_events (user_id, created_at DESC);

-- ---------------------------------------------------------------------
-- 5. 金融消费者投诉（domain.compliance）
--    渠道：平台 PLATFORM | 金融消费者中心 CONSUMER_CENTER | NBC 热线 NBC_HOTLINE
--    状态：RECEIVED 受理 | PROCESSING 处理中 | RESOLVED 已解决
-- ---------------------------------------------------------------------
CREATE TABLE claw.financial_consumer_complaints (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_no VARCHAR(64)  NOT NULL UNIQUE,
    user_id      BIGINT       NOT NULL REFERENCES claw.users (id),
    channel      VARCHAR(32)  NOT NULL,
    subject      VARCHAR(255) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED',
    resolution   VARCHAR(255),
    resolved_by  BIGINT       REFERENCES claw.users (id),
    resolved_at  TIMESTAMPTZ,
    tenant_id    BIGINT       NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_complaints_user   ON claw.financial_consumer_complaints (user_id, status);
CREATE INDEX idx_complaints_status ON claw.financial_consumer_complaints (status, created_at DESC);

-- ---------------------------------------------------------------------
-- 6. 种子：3 个电池 BMS 设备（关联 V6 种子电池）
-- ---------------------------------------------------------------------
INSERT INTO claw.devices (asset_id, device_type, imei, protocol_ver)
SELECT a.id, 'BATTERY_BMS', v.imei, 'CAN2.0B'
FROM claw.assets a JOIN (VALUES
    ('BAT-PP-001', 'IMEI-B001'),
    ('BAT-PP-002', 'IMEI-B002'),
    ('BAT-PP-003', 'IMEI-B003')
) AS v(no, imei) ON a.asset_no = v.no;
