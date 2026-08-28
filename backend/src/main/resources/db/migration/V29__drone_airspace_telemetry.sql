-- V29：无人机资产类别 + 低空经济专属域（空域/飞行计划/飞手资质/作业计量）+ IoT 遥测快照。
-- 复用 P0 资产状态机/绑定/生命周期扫描器，仅新增航空差异化表与数字孪生数据源。
SET search_path = claw;

-- 无人机扩展（资产子类型，镜像 vehicles/batteries）
CREATE TABLE IF NOT EXISTS drones (
  asset_id             BIGINT PRIMARY KEY REFERENCES assets(id),
  remote_id           VARCHAR(64) NOT NULL UNIQUE,   -- Remote ID 广播码（合规）
  model               VARCHAR(120) NOT NULL,
  max_flight_time_min INT,
  max_payload_kg       NUMERIC(10,2),
  payload_type        VARCHAR(16),                   -- SPRAY/CARGO/SLING/THERMAL/RECON
  airworthiness_cert_no VARCHAR(64),                 -- SSCA 适航证
  pilot_license_no   VARCHAR(64),
  flight_minutes     BIGINT DEFAULT 0,               -- 累计飞行时长（扫描器据其退役）
  protocol_ver       VARCHAR(32),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 空域分区（地理围栏）
CREATE TABLE IF NOT EXISTS airspace_zones (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(120) NOT NULL,
  level       VARCHAR(16) NOT NULL,                 -- OPERATIONAL/RESTRICTED/NFZ
  center_lat  NUMERIC(10,7) NOT NULL,
  center_lng  NUMERIC(10,7) NOT NULL,
  radius_m    INT NOT NULL,
  country     VARCHAR(8) NOT NULL DEFAULT 'KH',
  note        TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 飞行计划（合规前置：指定空域分区 + 飞手资质）
CREATE TABLE IF NOT EXISTS flight_plans (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id    BIGINT NOT NULL REFERENCES assets(id),
  zone_id     BIGINT NOT NULL REFERENCES airspace_zones(id),
  pilot_id    BIGINT NOT NULL,
  planned_at  TIMESTAMPTZ NOT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'DRAFT', -- DRAFT/APPROVED/ACTIVE/COMPLETED/VIOLATED
  route_note  TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_flightplan_asset ON flight_plans (asset_id);

-- 飞手资质（SSCA 签发，按作业类型分级）
CREATE TABLE IF NOT EXISTS pilot_licenses (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  license_no    VARCHAR(64) NOT NULL UNIQUE,
  holder_name   VARCHAR(120) NOT NULL,
  ltype         VARCHAR(16) NOT NULL,               -- AGRICULTURE/LOGISTICS/INSPECTION/RESCUE
  issuer        VARCHAR(120) NOT NULL DEFAULT 'SSCA',
  expiry_date   DATE NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 无人机作业计量（分账/任务计量依据）
CREATE TABLE IF NOT EXISTS drone_missions (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id      BIGINT NOT NULL REFERENCES assets(id),
  mission_type  VARCHAR(16) NOT NULL,               -- SPRAY/CARGO/INSPECTION/RESCUE
  payload_desc  VARCHAR(200),
  area_ha       NUMERIC(10,2),                       -- 喷洒公顷（SPRAY）
  trips         INT,                                 -- 配送趟数（CARGO）
  flight_minutes INT,
  pilot_id      BIGINT NOT NULL,
  executed_at   TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_dronemission_asset ON drone_missions (asset_id);

-- IoT 遥测快照（数字孪生数据源；生命周期扫描器 SOH/位置读自此表）
CREATE TABLE IF NOT EXISTS telemetry (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id    BIGINT NOT NULL UNIQUE REFERENCES assets(id),
  soh         NUMERIC(5,2),                          -- 健康度 %
  lat         NUMERIC(10,7),
  lng         NUMERIC(10,7),
  speed_kph   NUMERIC(8,2),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_telemetry_asset ON telemetry (asset_id);
