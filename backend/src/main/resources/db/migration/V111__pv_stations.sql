SET search_path = claw;

-- 光伏电站扩展（光伏追溯切片）。
-- AssetService 建档 PV_STATION 时按 asset_id 建一行；rated_power_wp 为铭牌装机容量，
-- 是 PR（性能比）的分母来源。建档时若无铭牌输入则留空，PR 随之返回 null（不编造分母）。
CREATE TABLE IF NOT EXISTS claw.pv_stations (
    id                BIGSERIAL PRIMARY KEY,
    asset_id          BIGINT NOT NULL UNIQUE,
    rated_power_wp    NUMERIC(12,2),      -- 铭牌装机容量 Wp（PR 分母）
    grid_connection_no VARCHAR(64),       -- 并网编号/购售电合同号
    installed_at      DATE,
    tilt_deg          NUMERIC(5,2),       -- 安装倾角
    azimuth_deg       NUMERIC(6,2),       -- 方位角（0=正北，180=正南）
    module_count      INTEGER,            -- 组件总块数
    operator_id       BIGINT,             -- 运维方/业主
    tenant_id         BIGINT NOT NULL DEFAULT 1,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pv_stations_operator ON claw.pv_stations (operator_id);
