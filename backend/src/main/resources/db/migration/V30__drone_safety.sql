-- V30：无人机飞行安全管控（类比车辆断缴锁车）。
-- 任一条 OPEN 安全事件即判定资产 LOCKED，禁止起飞；resolve 后恢复 NORMAL。
-- 这是低空经济合规闭环的"安全闸"：不能只管飞不管安全。
SET search_path = claw;

CREATE TABLE IF NOT EXISTS drone_safety_events (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id    BIGINT NOT NULL REFERENCES assets(id),
  cause       VARCHAR(24) NOT NULL,                 -- GEOFENCE_VIOLATION/LOST_LINK/LOW_BATTERY/MANUAL
  status      VARCHAR(16) NOT NULL DEFAULT 'OPEN',  -- OPEN/RESOLVED
  detail      TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_safety_asset ON drone_safety_events (asset_id);
CREATE INDEX IF NOT EXISTS idx_safety_open ON drone_safety_events (asset_id, status);
