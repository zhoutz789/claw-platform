SET search_path = claw;

-- 光伏小时发电量表（光伏数据链路切片）。
--
-- 计量铁律：energy_wh 只由「累计计数器差分」得到（INVERTER 取 total_yield_wh，METER 取
-- forward_total_wh），绝不用功率对时间积分——功率采样有丢包/抖动，积分会系统性偏移，
-- 而累计计数器是厂家表底读数，与结算口径一致（与换电结算同一计量铁律）。
--
-- 双来源隔离：INVERTER 与 METER 是两条独立计量链，唯一索引含 source，
-- 两来源各占一行，永不在同一行内混算（口径不同：逆变器计发电、电表计上网）。
--
-- 幂等：UNIQUE (device_no, bucket_at, source) —— 同一小时重复上报只更新同一行，
-- 且因差分基于已存 cumulative_wh，重复上报同一表底读数差分为 0，不会累加。

CREATE TABLE IF NOT EXISTS claw.pv_generation_hourly (
    id               BIGSERIAL PRIMARY KEY,
    station_asset_id BIGINT,
    device_no        VARCHAR(64) NOT NULL,
    source           VARCHAR(16) NOT NULL,          -- INVERTER / METER（两来源永不可混算）
    bucket_at        TIMESTAMP   NOT NULL,          -- 整点（小时桶起点，UTC 截断）
    energy_wh        NUMERIC(18,4),                 -- 该小时电量，由累计计数器差分得到
    cumulative_wh    NUMERIC(18,4),                 -- 该时刻累计计数器读数（表底，审计锚点）
    peak_power_w     NUMERIC(11,2),                 -- 该小时峰值功率
    irradiance_avg   NUMERIC(10,2),                 -- 该小时辐照度（W/m²）
    pr               NUMERIC(6,4),                  -- 性能比 PR（需装机容量，本切片留空）
    tenant_id        BIGINT      NOT NULL DEFAULT 1,
    created_at       TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pv_generation_hourly_device_bucket_source
    ON claw.pv_generation_hourly (device_no, bucket_at, source);

CREATE INDEX IF NOT EXISTS idx_pv_generation_hourly_station
    ON claw.pv_generation_hourly (station_asset_id, bucket_at DESC);
