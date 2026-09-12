SET search_path = claw;

-- 车辆 ↔ 电池 绑定（换电/充电三视图基础表）。
-- vehicle_id / battery_id 引用 vehicles(asset_id) / batteries(asset_id)：
-- vehicles 与 batteries 的主键列为 asset_id（见 V1__init_core_tables），而非 id。
CREATE TABLE IF NOT EXISTS claw.vehicle_battery_bindings (
    id           BIGSERIAL PRIMARY KEY,
    vehicle_id   BIGINT NOT NULL,
    battery_id   BIGINT NOT NULL,
    bound_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    unbound_at   TIMESTAMPTZ,
    protocol_ver VARCHAR(32),
    CONSTRAINT fk_vbb_vehicle FOREIGN KEY (vehicle_id) REFERENCES claw.vehicles (asset_id),
    CONSTRAINT fk_vbb_battery FOREIGN KEY (battery_id) REFERENCES claw.batteries (asset_id)
);

-- 当前绑定查询（按车辆找未解绑记录）；电池侧历史查询。
CREATE INDEX IF NOT EXISTS idx_vbb_vehicle ON claw.vehicle_battery_bindings (vehicle_id, unbound_at);
CREATE INDEX IF NOT EXISTS idx_vbb_battery ON claw.vehicle_battery_bindings (battery_id);
