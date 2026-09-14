-- ============================================================================
-- V140 无人机切片 2 —— 无人机 ↔ 电池绑定（schema=claw，镜像 V124）
--
-- 依据：《无人机资产接入平台-增量技术设计方案-v3.md》§3.2 / §4（R2 电池供需）
--   drone_battery_binding(id, drone_asset_id, battery_asset_id, bound_at, unbound_at, cycles, active)
--
-- 语义：一条记录代表「某无人机在某时间段内使用某块电池」；
--   active=TRUE 且 unbound_at IS NULL 表示当前生效绑定，同一无人机任一时刻至多一条。
--   cycles 记该次绑定期间的循环次数（热插拔换电 / 机场充电计量用）。
--
-- ⚠️ 外键目标列：drones 与 batteries 的主键均为 asset_id（V29 / V1），非 id。
--    与 V124 的 vehicle_battery_bindings → vehicles(asset_id) / batteries(asset_id) 一致。
-- ⚠️ 幂等：CREATE TABLE / INDEX 全部 IF NOT EXISTS，二次执行无副作用。
-- ⚠️ 不动 V1–V139；本迁移接在 V139 之后。
-- ============================================================================

SET search_path = claw;

CREATE TABLE IF NOT EXISTS drone_battery_binding (
    id               BIGSERIAL   PRIMARY KEY,
    drone_asset_id   BIGINT      NOT NULL,
    battery_asset_id BIGINT      NOT NULL,
    bound_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    unbound_at       TIMESTAMPTZ,
    cycles           INT         NOT NULL DEFAULT 0,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_dbb_drone   FOREIGN KEY (drone_asset_id)   REFERENCES drones (asset_id),
    CONSTRAINT fk_dbb_battery FOREIGN KEY (battery_asset_id) REFERENCES batteries (asset_id)
);

-- 当前绑定查询（按无人机找 active 记录）；电池侧历史查询。
CREATE INDEX IF NOT EXISTS idx_dbb_drone   ON drone_battery_binding (drone_asset_id, active);
CREATE INDEX IF NOT EXISTS idx_dbb_battery ON drone_battery_binding (battery_asset_id, active);
