-- =====================================================================
-- Claw 平台 V52 增量（商家入驻骨架 · 已确认本轮回填骨架）
-- 依据：增量PRD/设计 决策「商家域：本轮回填骨架（merchants 表 + 服务站区块/铺位模型 + 基础 CRUD）」，
--       完整招商审批流留 Phase 2（P2-1）。
-- 范围：
--   · merchants（商家主体）
--   · stations.merchant_id（服务站 ↔ 商家关联入口，本轮回填）
--   · merchant_zones（商家区块/招商片区）
--   · merchant_booths（铺位：区块下的具体可招商铺位）
-- 全量幂等。
-- =====================================================================

SET search_path = claw;

CREATE TABLE IF NOT EXISTS merchants (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(64) NOT NULL UNIQUE,
    name         VARCHAR(160) NOT NULL,
    contact      VARCHAR(160),
    country      VARCHAR(64),
    status       VARCHAR(24) NOT NULL DEFAULT 'PENDING',   -- PENDING(待审核)/ACTIVE/REJECTED（审批流 Phase 2）
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted      BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 服务站 ↔ 商家关联入口（本轮回填，招商审批流 Phase 2）
ALTER TABLE stations ADD COLUMN IF NOT EXISTS merchant_id BIGINT REFERENCES merchants (id);

-- 商家区块/招商片区
CREATE TABLE IF NOT EXISTS merchant_zones (
    id           BIGSERIAL PRIMARY KEY,
    merchant_id  BIGINT       NOT NULL REFERENCES merchants (id),
    zone_code    VARCHAR(64)  NOT NULL,
    name         VARCHAR(160) NOT NULL,
    station_id   BIGINT       REFERENCES stations (id),     -- 关联服务站（可选）
    status       VARCHAR(24)  NOT NULL DEFAULT 'OPEN',       -- OPEN/LOCKED
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, zone_code)
);

-- 铺位：区块下的具体可招商铺位
CREATE TABLE IF NOT EXISTS merchant_booths (
    id           BIGSERIAL PRIMARY KEY,
    zone_id      BIGINT       NOT NULL REFERENCES merchant_zones (id) ON DELETE CASCADE,
    booth_code   VARCHAR(64)  NOT NULL,
    name         VARCHAR(160),
    area_sqm     NUMERIC(10,2),
    monthly_rent NUMERIC(12,2),
    status       VARCHAR(24)  NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE/RESERVED/LEASED
    tenant_user_id BIGINT     REFERENCES users (id),          -- 承租方（Phase 2 招商签约后回填）
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (zone_id, booth_code)
);
CREATE INDEX IF NOT EXISTS idx_mb_zone ON merchant_booths (zone_id);
