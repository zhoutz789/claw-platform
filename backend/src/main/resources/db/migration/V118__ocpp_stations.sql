-- ============================================================================
-- V118 OCPP 充电桩接入：站点 / 连接器 / 交易 / 报文日志 四张基表
-- 仅新建，绝不修改 V1–V117 任一文件（Flyway checksum 约束）。
-- 充电桩从「能建档」(AssetType.CHARGER) 升级为「能调度」（OCPP 1.6J 通信层）。
-- ============================================================================

CREATE TABLE claw.ocpp_charging_stations (
    charge_point_id   VARCHAR(64)  NOT NULL PRIMARY KEY,   -- OCPP chargePointId（= WS 路径、= device_no）
    asset_id          BIGINT,                                -- 关联 assets.id（AssetType=CHARGER）
    vendor            VARCHAR(64),
    model             VARCHAR(64),
    firmware          VARCHAR(64),
    status            VARCHAR(16)  NOT NULL DEFAULT 'OFFLINE', -- ONLINE / OFFLINE / FAULT
    last_heartbeat    TIMESTAMP,
    auth_token        VARCHAR(128),                          -- BootNotification 鉴权令牌（缺省可空=免校验）
    created_at        TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE claw.ocpp_connectors (
    id                BIGSERIAL    PRIMARY KEY,
    station_id        VARCHAR(64)  NOT NULL,                 -- 关联 ocpp_charging_stations.charge_point_id
    connector_id      INT          NOT NULL,                 -- OCPP connectorId（单桩多为 1）
    asset_id          BIGINT,                                -- 连接器级资产（一般复用站点资产）
    status            VARCHAR(16)  NOT NULL DEFAULT 'UNAVAILABLE', -- Available / Occupied / Faulted / Unavailable
    max_power_w       NUMERIC(12,2),
    last_meter_wh     NUMERIC(18,2),                         -- 最近累计电表读数 Wh
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (station_id, connector_id)
);

CREATE TABLE claw.ocpp_transactions (
    id                BIGSERIAL    PRIMARY KEY,
    station_id        VARCHAR(64)  NOT NULL,
    connector_id      INT          NOT NULL,
    id_tag            VARCHAR(64),                           -- 充电卡 / 用户标识
    start_wh          NUMERIC(18,2),                         -- 起充时电表读数 Wh
    stop_wh           NUMERIC(18,2),                         -- 结束电表读数 Wh
    start_at          TIMESTAMP,
    stop_at           TIMESTAMP,
    meter_wh          NUMERIC(18,2),                         -- 本次累计电量 Wh（= stop_wh - start_wh）
    status            VARCHAR(16)  NOT NULL DEFAULT 'INPROGRESS', -- INPROGRESS / COMPLETED
    transaction_id    INT,                                  -- OCPP 事务号（StartTransaction 返回）
    asset_id          BIGINT                                -- 关联 assets.id（结算溯源）
);

CREATE TABLE claw.ocpp_message_log (
    id                BIGSERIAL    PRIMARY KEY,
    station_id        VARCHAR(64)  NOT NULL,
    direction         VARCHAR(8)   NOT NULL,                 -- IN / OUT
    msg_type          VARCHAR(16)  NOT NULL,                 -- CALL / CALLRESULT / CALLERROR
    msg_id            VARCHAR(36),                           -- OCPP 消息唯一 id
    payload_json      TEXT,                                  -- 原始 JSON 报文（排障 + 合规）
    at                TIMESTAMP    NOT NULL DEFAULT now()
);
