-- =====================================================================
-- Claw 平台 V48 增量（库存 + 合格证基建 · 增量 B 基础）
-- 依据：增量设计-权限骨架与库存流转域.md（V48 库存+合格证）
-- 范围：
--   · inventory（运营库存台账，每资产一行；OWNED_BY_MFG 自有 / CONSIGNED 寄售）
--   · lifecycle_events（流通链事件流，替代设计里的 lifecycle_events；1 设备 1 当前状态冗余在 inventory.current_status）
--   · custody_records（寄售占有权真源；本平台无 custody 表，故新建 custody_records，1:1 对应 inventory）
-- 说明：合格证复用既有 claw.certificates（V34 已建，cert_type=DEVICE），本脚本不重复建表。
-- 全量幂等。
-- =====================================================================

SET search_path = claw;

-- ---------------------------------------------------------------------
-- 1. 运营库存台账（每资产一行）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inventory (
    id                  BIGSERIAL PRIMARY KEY,
    asset_id            BIGINT       NOT NULL REFERENCES assets (id) UNIQUE,  -- 一设备一库存行
    ownership_type      VARCHAR(20)  NOT NULL,                  -- OWNED_BY_MFG（自有）/ CONSIGNED（寄售）
    owner_manufacturer_id BIGINT     REFERENCES manufacturers (id),           -- 货权方（始终厂家）
    holder_station_id   BIGINT       REFERENCES stations (id),                -- 寄售持有站；自有为 NULL
    custody_id          BIGINT,                                     -- 寄售时关联占有权记录（custody_records.id）
    product_id          BIGINT       REFERENCES products (id),                 -- 实例化来源
    serial_number       VARCHAR(64),                                -- 序列号（冗余，便于查询）
    current_status      VARCHAR(20)  NOT NULL,                      -- 同 LifecycleStatus 冗余（PRODUCING…RECALLED）
    inbound_at          TIMESTAMPTZ,                                -- 入寄售库时点（Q1 回收起算点）
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 统一以 device_id 为库存粒度（本平台"设备=devices"，资产=assets，两者 1:1 且设备为运营主体）。
-- assetId 列保留用于产权链回溯；deviceId 为业务代码实际使用的主键语义。
ALTER TABLE inventory ADD COLUMN IF NOT EXISTS device_id BIGINT REFERENCES devices (id);

CREATE INDEX IF NOT EXISTS idx_inv_owner       ON inventory (owner_manufacturer_id);
CREATE INDEX IF NOT EXISTS idx_inv_holder      ON inventory (holder_station_id);
CREATE INDEX IF NOT EXISTS idx_inv_status      ON inventory (current_status);
CREATE INDEX IF NOT EXISTS idx_inv_ownership   ON inventory (ownership_type);
CREATE INDEX IF NOT EXISTS idx_inv_device      ON inventory (device_id);

-- ---------------------------------------------------------------------
-- 2. 流通链事件流（设备生命周期状态机 + 责任倒查）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lifecycle_events (
    id           BIGSERIAL PRIMARY KEY,
    asset_id     BIGINT       NOT NULL REFERENCES assets (id),
    from_status  VARCHAR(20),
    to_status    VARCHAR(20)  NOT NULL,
    event_type   VARCHAR(30)  NOT NULL,   -- PRODUCE/CERTIFY/SHIP/RECEIVE/TRANSFER_OUT/TRANSFER_IN/PICKUP/DEPLOY/RECALL/RETURN
    operator_id  BIGINT,
    station_id   BIGINT,
    order_ref    VARCHAR(40),
    custody_ref  BIGINT,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 实体 LifecycleEvent.deviceId 是业务主体字段，统一列名（原 asset_id 与本域代码语义不一致）。
ALTER TABLE lifecycle_events DROP COLUMN IF EXISTS asset_id;
ALTER TABLE lifecycle_events ADD COLUMN IF NOT EXISTS device_id BIGINT NOT NULL REFERENCES devices (id);

CREATE INDEX IF NOT EXISTS idx_lc_device      ON lifecycle_events (device_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_lc_occurred    ON lifecycle_events (occurred_at);

-- ---------------------------------------------------------------------
-- 3. 寄售占有权记录（custody 真源；本平台无 custody 表，新建 custody_records）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS custody_records (
    id              BIGSERIAL PRIMARY KEY,
    asset_id        BIGINT       NOT NULL REFERENCES assets (id),
    manufacturer_id BIGINT       REFERENCES manufacturers (id),   -- 货权方
    station_id      BIGINT       REFERENCES stations (id),        -- 当前持有站（寄售）
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',       -- ACTIVE/TRANSFERRED_OUT/RETURNED/RELEASED
    since           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    liability_holder VARCHAR(20) DEFAULT 'MANUFACTURER',          -- 在途责任方（Q2）：SOURCE_STATION/MANUFACTURER
    transferred_at  TIMESTAMPTZ,                                  -- 占有权转移时点（Q2 扫码交接）
    ended_at        TIMESTAMPTZ,
    ended_reason    VARCHAR(40),
    transfer_order_id BIGINT,                                    -- 关联调拨单（可空）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cr_asset    ON custody_records (asset_id, status);
CREATE INDEX IF NOT EXISTS idx_cr_station ON custody_records (station_id, status);
CREATE INDEX IF NOT EXISTS idx_cr_mfg     ON custody_records (manufacturer_id);

-- 合格证类型扩充 DEVICE（既有的 CertificateType 枚举已加 DEVICE，仅补充种子说明，无需改表）
-- 设备合格证通过既有 claw.certificates（cert_type='DEVICE', asset_id=设备资产）出具，生成即写库不可补。
