-- =====================================================================
-- Claw 平台 V49 增量（站间调拨单 · R5 增量 B）
-- 依据：增量设计-权限骨架与库存流转域.md（V49 调拨单）
-- 范围：
--   · transfer_orders（调拨单）
--   · transfer_order_items（调拨明细，1 设备 1 次调拨；在途期间唯一）
-- 说明：占有权真源为 custody_records（V48 新建），源站交接关旧 custody、目标站收货开新 custody。
-- 全量幂等。
-- =====================================================================

SET search_path = claw;

CREATE TABLE IF NOT EXISTS transfer_orders (
    id               BIGSERIAL PRIMARY KEY,
    transfer_no      VARCHAR(40) NOT NULL UNIQUE,
    manufacturer_id  BIGINT       NOT NULL REFERENCES manufacturers (id),  -- 发起方/物流费承担
    from_station_id  BIGINT       NOT NULL REFERENCES stations (id),        -- 源站
    to_station_id    BIGINT       NOT NULL REFERENCES stations (id),        -- 目标站
    status           VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',                 -- DRAFT/CREATED/IN_TRANSIT/COMPLETED/CANCELLED
    logistics_fee    NUMERIC(12,2) DEFAULT 0,                              -- 厂家承担
    handover_at      TIMESTAMPTZ,                                          -- 源站扫码交接时点（占有权转移点，Q2）
    receive_at       TIMESTAMPTZ,                                          -- 目标站收货时点
    completed_at     TIMESTAMPTZ,
    created_by       BIGINT       REFERENCES users (id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_to_no      ON transfer_orders (transfer_no);
CREATE INDEX IF NOT EXISTS idx_to_mfg     ON transfer_orders (manufacturer_id);
CREATE INDEX IF NOT EXISTS idx_to_status  ON transfer_orders (status);

CREATE TABLE IF NOT EXISTS transfer_order_items (
    id               BIGSERIAL PRIMARY KEY,
    transfer_order_id BIGINT      NOT NULL REFERENCES transfer_orders (id) ON DELETE CASCADE,
    asset_id         BIGINT       REFERENCES assets (id),                    -- 产权链回溯（可空）
    device_id        BIGINT       NOT NULL REFERENCES devices (id),          -- 调拨主体设备（业务粒度）
    from_custody_id  BIGINT,                                               -- 源占有权（交接后关闭）
    to_custody_id    BIGINT,                                               -- 目标占有权（收货后开）
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (transfer_order_id, device_id)
);
CREATE INDEX IF NOT EXISTS idx_toi_asset  ON transfer_order_items (asset_id);
CREATE INDEX IF NOT EXISTS idx_toi_device ON transfer_order_items (device_id);
