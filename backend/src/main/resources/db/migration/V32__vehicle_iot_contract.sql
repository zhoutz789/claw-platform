-- =====================================================================
-- Claw 平台 V32 增量：车辆 IoT 终端对接契约
-- 对齐《车辆物联网终端采购对接技术选型书》（MQTT 报文契约）
--   · 入站 claw/iot/{deviceNo}/up ：location / status / cmd_ack 三报文
--   · 出站 claw/iot/{deviceNo}/down ：指令（签名 + 防重放 + 安全条件）
--   · 设备身份 device_no（= 选型书 DeviceID / 二维码内容） + secret（HMAC 密钥）
-- schema = claw（ddl-auto=none，由 Flyway 全权管理，禁止 Hibernate 自动改表）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. devices：扩展车辆终端身份与控制态
-- ---------------------------------------------------------------------
ALTER TABLE claw.devices
    ADD COLUMN IF NOT EXISTS device_no   VARCHAR(64) UNIQUE,        -- 设备唯一编号 = DeviceID = 二维码内容
    ADD COLUMN IF NOT EXISTS secret      VARCHAR(128),              -- 下行指令 HMAC-SHA256 签名密钥
    ADD COLUMN IF NOT EXISTS relay_state SMALLINT      NOT NULL DEFAULT 0,  -- 继电器/开关 0断 1通
    ADD COLUMN IF NOT EXISTS qr_payload  TEXT;                       -- 二维码载体（device_no 或带平台地址的 JSON）

-- ---------------------------------------------------------------------
-- 2. telemetry_latest：扩展车辆遥测字段（兼容老 BMS 字段 soc/soh/humid/faults）
-- ---------------------------------------------------------------------
ALTER TABLE claw.telemetry_latest
    ADD COLUMN IF NOT EXISTS acc             SMALLINT,               -- 点火状态 0=熄火 1=点火
    ADD COLUMN IF NOT EXISTS battery_voltage NUMERIC(5,2),           -- 电瓶电压 V
    ADD COLUMN IF NOT EXISTS rssi            INTEGER,                -- 信号强度 dBm
    ADD COLUMN IF NOT EXISTS course          NUMERIC(7,4),           -- 方向角
    ADD COLUMN IF NOT EXISTS altitude        NUMERIC(8,2),           -- 海拔 m
    ADD COLUMN IF NOT EXISTS relay_state     SMALLINT,               -- 继电器状态 0/1
    ADD COLUMN IF NOT EXISTS door_state      SMALLINT,               -- 门磁 0/1
    ADD COLUMN IF NOT EXISTS vib_state       SMALLINT,               -- 震动 0/1
    ADD COLUMN IF NOT EXISTS alarms          JSONB;                  -- 告警列表 power_off/tamper/geo_fence/low_batt/vib

-- ---------------------------------------------------------------------
-- 3. device_commands：下行指令 + 上行 cmd_ack 关联（指令全生命周期审计）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS claw.device_commands (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id   BIGINT       NOT NULL REFERENCES claw.devices (id),
    device_no   VARCHAR(64)  NOT NULL,
    action      VARCHAR(16)  NOT NULL,     -- relay | lock | config | reboot | ota
    params_json JSONB,                      -- 下行参数（含 safeCond 安全条件）
    cmd_id      VARCHAR(64)  NOT NULL,      -- 指令唯一标识（防重放 + 回执关联）
    nonce       VARCHAR(64),
    sign        VARCHAR(128),               -- HMAC-SHA256(报文, secret)
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING | OK | FAIL
    result      VARCHAR(8),
    detail      VARCHAR(255),
    tenant_id   BIGINT       NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    acked_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_devcmd_device ON claw.device_commands (device_no, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_devcmd_cmdid  ON claw.device_commands (cmd_id);
