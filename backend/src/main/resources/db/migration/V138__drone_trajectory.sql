-- ============================================================================
-- V138 无人机航迹时序表（schema=claw）—— 切片 1
-- 资产级历史航迹（镜像 V123__vehicle_trajectory.sql）：供轨迹回放 / 围栏判定 / 作业计量依据。
-- 新增航空维度：alt_m(相对高度) / speed_mps(米每秒) / battery_pct(电量) /
--   pos_mode(RTK/PPK/GNSS，链路断即降级) / source(上报来源) / flight_no(架次号)。
-- 不动 V1–V136；本迁移接在 V137 之后。
-- ============================================================================

CREATE TABLE IF NOT EXISTS claw.drone_trajectory (
    id           BIGSERIAL        PRIMARY KEY,
    asset_id     BIGINT           NOT NULL,
    ts           TIMESTAMPTZ      NOT NULL DEFAULT now(),  -- 航迹点时刻
    lat          DOUBLE PRECISION,
    lng          DOUBLE PRECISION,
    alt_m        DOUBLE PRECISION,                        -- 相对高度(米)
    speed_mps    DOUBLE PRECISION,                        -- 速度(米/秒)
    heading      DOUBLE PRECISION,                        -- 航向角(度)
    battery_pct  DOUBLE PRECISION,                        -- 电量百分比(0-100)
    pos_mode     VARCHAR(16),                             -- RTK / PPK / GNSS
    source       VARCHAR(32),                             -- 上报来源(FCU/RTK/EDGE)
    flight_no    VARCHAR(64)                              -- 架次号
);

CREATE INDEX IF NOT EXISTS idx_drone_trajectory_asset_ts ON claw.drone_trajectory (asset_id, ts DESC);
