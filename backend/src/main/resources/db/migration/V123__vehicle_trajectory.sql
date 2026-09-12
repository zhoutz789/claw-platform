-- ============================================================================
-- V123 车辆轨迹时序表（schema=claw）
-- 资产级历史轨迹（复用 telemetry 资产级定位思路）：供轨迹回放 / 围栏判定 / 里程退役依据。
-- TimescaleDB hypertable 在具备扩展时自动转换，无扩展环境（如本地 H2）则退化为普通表，安全。
-- 不动 V1–V122；本迁移接在 V122 之后。
-- ============================================================================

CREATE TABLE claw.vehicle_trajectory (
    id           BIGSERIAL      PRIMARY KEY,
    asset_id     BIGINT         NOT NULL,
    t            TIMESTAMPTZ    NOT NULL DEFAULT now(),  -- 轨迹点时刻
    lat          DOUBLE PRECISION,
    lng          DOUBLE PRECISION,
    speed_kph    DOUBLE PRECISION,
    heading      DOUBLE PRECISION,                        -- 航向角(度)
    odometer_km  BIGINT         NOT NULL DEFAULT 0,       -- 累计里程(km)，里程退役阈值依据
    soc          DOUBLE PRECISION                         -- 电量百分比(0-100)
);

CREATE INDEX idx_vehicle_trajectory_asset_t ON claw.vehicle_trajectory (asset_id, t);

-- 里程退役 / 资产生命周期阈值（系统可配；默认 20 万公里，仿 DRONE_RETIRE_FLIGHT_MIN 模式）
INSERT INTO claw.system_config (config_key, config_value, description, updated_at)
VALUES ('VEHICLE_RETIRE_ODOMETER_KM', '200000',
        '车辆累计里程达到该值触发退役评估（资产生命周期调度器读取）', now())
ON CONFLICT (config_key) DO NOTHING;

-- 若 TimescaleDB 扩展存在则转 hypertable（无扩展则跳过，表仍可用）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        PERFORM create_hypertable('claw.vehicle_trajectory', 't', if_not_exists => true);
    END IF;
END $$;
