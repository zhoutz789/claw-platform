SET search_path = claw;

-- 光伏组件级溯源（光伏追溯切片）：回答「这块板子哪来的、装在哪」。
-- serial_no 唯一 = 组件身份证，支撑按序列号反查批次/厂家/安装位置/所在逆变器。
CREATE TABLE IF NOT EXISTS claw.pv_modules (
    id                 BIGSERIAL PRIMARY KEY,
    station_asset_id   BIGINT,
    serial_no          VARCHAR(64) NOT NULL UNIQUE,
    product_id         BIGINT,
    sku_id             BIGINT,
    manufacturer_id    BIGINT,
    batch_no           VARCHAR(64),
    pmax_w             NUMERIC(8,2),      -- 标称峰值功率 W
    degradation_rate   NUMERIC(5,4),      -- 年衰减率（小数，如 0.0050 = 0.5%/年）
    installed_at       DATE,
    string_id          VARCHAR(32),       -- 所属组串
    position           INTEGER,           -- 组内位置序号
    inverter_device_no VARCHAR(64),       -- 所接逆变器设备号（关联小时电量）
    certificate_id     BIGINT,            -- 出厂/认证证书
    el_image_url       VARCHAR(255),      -- EL 隐裂检测图
    tenant_id          BIGINT NOT NULL DEFAULT 1,
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pv_modules_batch ON claw.pv_modules (batch_no);
CREATE INDEX IF NOT EXISTS idx_pv_modules_station ON claw.pv_modules (station_asset_id);
CREATE INDEX IF NOT EXISTS idx_pv_modules_inverter ON claw.pv_modules (inverter_device_no);
