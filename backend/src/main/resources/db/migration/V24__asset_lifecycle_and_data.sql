-- V24：资产全生命周期数据（阶段/维修/使用/车辆运营）。闭合「溯源/回收/销毁 + 维修/使用/营收运营」。
SET search_path = claw;

CREATE TABLE IF NOT EXISTS asset_lifecycle_events (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id      BIGINT NOT NULL REFERENCES assets(id),
  stage         VARCHAR(24) NOT NULL,  -- PRODUCED/IN_TRANSIT/IN_USE/MAINTENANCE/RECYCLED/DESTROYED
  location      VARCHAR(160),
  operator_id   BIGINT,
  note          TEXT,
  occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_lifecycle_asset ON asset_lifecycle_events (asset_id, occurred_at);

CREATE TABLE IF NOT EXISTS asset_maintenance_records (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id      BIGINT NOT NULL REFERENCES assets(id),
  serviced_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  mtype         VARCHAR(32),            -- 维修类型
  vendor        VARCHAR(120),
  cost          NUMERIC(18,4) DEFAULT 0,
  note          TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_maint_asset ON asset_maintenance_records (asset_id, serviced_at);

CREATE TABLE IF NOT EXISTS asset_usage_records (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id      BIGINT NOT NULL REFERENCES assets(id),
  period_start  TIMESTAMPTZ,
  period_end    TIMESTAMPTZ,
  mileage_km    NUMERIC(18,2) DEFAULT 0,
  cycles        INT DEFAULT 0,
  energy_kwh    NUMERIC(18,4) DEFAULT 0,
  note          TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_usage_asset ON asset_usage_records (asset_id, period_start);

CREATE TABLE IF NOT EXISTS asset_vehicle_ops (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id      BIGINT NOT NULL REFERENCES assets(id),
  op_type       VARCHAR(24) NOT NULL,   -- PASSENGER/LOGISTICS/MOBILE_SELL/ADVERTISING/RECORDING
  started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at      TIMESTAMPTZ,
  revenue       NUMERIC(18,4) DEFAULT 0,
  detail_json   TEXT,
  note          TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_vops_asset ON asset_vehicle_ops (asset_id, started_at);
