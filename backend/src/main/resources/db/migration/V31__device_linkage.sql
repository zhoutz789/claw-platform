-- =====================================================================
-- V31：设备数据四向联动闭环（设备数据 → 业务闭环）
--   1) telemetry_latest 增加 soh 列（电池/整机健康度，驱动退役生命周期）
--   2) device_linkage_events 联动审计表（资产档案/收益/风控/全生命周期四向落地记录）
-- 依据：周老板指令「设备数据变了要驱动：设备数据全要」——四类接入 + 四向联动全要
-- =====================================================================
SET search_path = claw;

-- 1. 健康度（SOH，%），电池 BMS / 整机通用，与 telemetry.soh 含义一致
ALTER TABLE telemetry_latest ADD COLUMN IF NOT EXISTS soh NUMERIC(5,2);

-- 2. 设备联动事件（四向闭环审计）
CREATE TABLE IF NOT EXISTS device_linkage_events (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id     BIGINT       NOT NULL REFERENCES assets (id),
    device_id    BIGINT       REFERENCES devices (id),
    direction    VARCHAR(16)  NOT NULL,   -- ASSET_UPDATE | REVENUE | RISK | LIFECYCLE
    trigger_type VARCHAR(24)  NOT NULL,   -- OPERATIONAL_SYNC | LOW_SOC | FAULT | SOH_LOW | USAGE ...
    payload      TEXT,                     -- 联动命中时的关键载荷（用量快照 / 触发阈值 JSON）
    status       VARCHAR(16)  NOT NULL DEFAULT 'DONE',  -- DONE | SKIPPED | ERROR
    triggered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_id    BIGINT       NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_linkage_asset ON device_linkage_events (asset_id, triggered_at DESC);
CREATE INDEX IF NOT EXISTS idx_linkage_dir   ON device_linkage_events (asset_id, direction);
