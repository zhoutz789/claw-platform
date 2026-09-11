SET search_path = claw;

-- 充电会话（锂电池 BMS 对接方案 Phase D，2026-09-11）。
-- 记录充电起止/电量/峰值功率/计费；电量以 BMS 累计 Wh 计数器为准（与换电结算同一计量铁律）。
CREATE TABLE IF NOT EXISTS claw.charge_sessions (
    id                  BIGSERIAL PRIMARY KEY,
    station_id          BIGINT,
    asset_id            BIGINT NOT NULL,
    device_no           VARCHAR(64),
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    started_at          TIMESTAMP NOT NULL,
    ended_at            TIMESTAMP,
    start_energy_wh     NUMERIC(18,4),
    end_energy_wh       NUMERIC(18,4),
    energy_delivered_wh NUMERIC(18,4),
    peak_power_w        NUMERIC(11,2),
    price_per_wh        NUMERIC(18,8),
    electricity_fee     NUMERIC(18,4),
    service_fee         NUMERIC(18,4),
    total_fee           NUMERIC(18,4),
    tenant_id           BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_charge_sessions_asset ON claw.charge_sessions (asset_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_charge_sessions_station ON claw.charge_sessions (station_id, started_at DESC);
