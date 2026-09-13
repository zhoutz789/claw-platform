-- ============================================================================
-- V123 车辆轨迹时序表（schema=claw）
-- 资产级历史轨迹（复用 telemetry 资产级定位思路）：供轨迹回放 / 围栏判定 / 里程退役依据。
-- TimescaleDB hypertable 在具备扩展时自动转换，无扩展环境（如本地 H2）则退化为普通表，安全。
-- 不动 V1–V122；本迁移接在 V122 之后。
--
-- ⚠️ 2026-09-13 修复（空库从零部署时暴露）：
--   原表 definition 用单列 PK(id)，新版 TimescaleDB 的 create_hypertable 因"唯一索引不含分区列 t"
--   直接报 SQLState TS103，导致整条迁移链在全新库上中断、后端崩溃重启。
--   修复：① 主键改复合 (id, t)；② create_hypertable 加异常降级（失败仅告警）。
--   说明：当时该库为全新空库（无任何迁移历史），改本文件不会触发 Flyway checksum 冲突。
-- ============================================================================

CREATE TABLE claw.vehicle_trajectory (
    id           BIGSERIAL      NOT NULL,
    asset_id     BIGINT         NOT NULL,
    t            TIMESTAMPTZ    NOT NULL DEFAULT now(),  -- 轨迹点时刻
    lat          DOUBLE PRECISION,
    lng          DOUBLE PRECISION,
    speed_kph    DOUBLE PRECISION,
    heading      DOUBLE PRECISION,                        -- 航向角(度)
    odometer_km  BIGINT         NOT NULL DEFAULT 0,       -- 累计里程(km)，里程退役阈值依据
    soc          DOUBLE PRECISION,                        -- 电量百分比(0-100)
    -- 复合主键：TimescaleDB 要求 hypertable 的所有唯一索引必须包含分区列 t，
    -- 单列 PK(id) 会被新版 create_hypertable 拒绝（SQLState TS103）。故主键取 (id, t)。
    -- id 仍为 BIGSERIAL，JPA @Id @GeneratedValue(IDENTITY) 语义不变（实体 VehicleTrajectory）。
    PRIMARY KEY (id, t)
);

CREATE INDEX idx_vehicle_trajectory_asset_t ON claw.vehicle_trajectory (asset_id, t);

-- 里程退役 / 资产生命周期阈值（系统可配；默认 20 万公里，仿 DRONE_RETIRE_FLIGHT_MIN 模式）
INSERT INTO claw.system_config (config_key, config_value, description, updated_at)
VALUES ('VEHICLE_RETIRE_ODOMETER_KM', '200000',
        '车辆累计里程达到该值触发退役评估（资产生命周期调度器读取）', now())
ON CONFLICT (config_key) DO NOTHING;

-- 若 TimescaleDB 扩展存在则转 hypertable；无扩展、或转换失败（不同 TimescaleDB 版本校验差异）时，
-- 一律降级为普通表并仅告警 —— 绝不因时序优化失败而中断整条 Flyway 迁移链。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        BEGIN
            PERFORM create_hypertable('claw.vehicle_trajectory', 't', if_not_exists => true);
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'skip create_hypertable(vehicle_trajectory): %', SQLERRM;
        END;
    END IF;
END $$;
