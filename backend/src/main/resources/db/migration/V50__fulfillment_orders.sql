-- =====================================================================
-- Claw 平台 V50 增量（待履约订单 + 结算台账 · R6/R7 增量 B）
-- 依据：增量设计-权限骨架与库存流转域.md（V50 待履约订单 + 结算）
-- 范围：
--   · fulfillment_orders（待履约订单：付款冻结 → 确认选品 → 发货 → 收货 → 取货扫码 → 结算）
--   · fulfillment_order_items（订单明细）
--   · fulfillment_settlements（结算台账：物流费 + 服务站提成 → 余额归厂家；复用 ledger 走资金，本表记结算单）
-- 说明：本平台无 settlements 表（仅有 revenue_settlements 光伏分成，不动），故新建 fulfillment_settlements。
--       资金冻结复用既有 account_entries（biz_type='FULFILL_FREEZE'）。用户付款方为 users（设计里 account_id 实为下单用户）。
-- 全量幂等。
-- =====================================================================

SET search_path = claw;

CREATE TABLE IF NOT EXISTS fulfillment_orders (
    id                BIGSERIAL PRIMARY KEY,
    order_no          VARCHAR(40) NOT NULL UNIQUE,
    customer_user_id  BIGINT       NOT NULL REFERENCES users (id),          -- 用户（付款方）
    manufacturer_id    BIGINT       NOT NULL REFERENCES manufacturers (id),
    station_id         BIGINT       NOT NULL REFERENCES stations (id),       -- 取货服务站（Q3 默认用户指定）
    remote_order       BOOLEAN      NOT NULL DEFAULT FALSE,                 -- 缺货远程代下单（B9）
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING_PAYMENT',      -- 见设计 §3.2
    total_amount       NUMERIC(12,2) DEFAULT 0,
    frozen_amount      NUMERIC(12,2) DEFAULT 0,                             -- 冻结资金
    payment_ref        VARCHAR(80),                                         -- 支付网关流水
    expire_at          TIMESTAMPTZ,                                         -- 履约超时时点（Q1 订单分支）
    paid_at            TIMESTAMPTZ,
    confirmed_at       TIMESTAMPTZ,
    shipped_at         TIMESTAMPTZ,
    received_at        TIMESTAMPTZ,
    picked_up_at       TIMESTAMPTZ,
    settled_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fo_no      ON fulfillment_orders (order_no);
CREATE INDEX IF NOT EXISTS idx_fo_customer ON fulfillment_orders (customer_user_id);
CREATE INDEX IF NOT EXISTS idx_fo_station  ON fulfillment_orders (station_id);
CREATE INDEX IF NOT EXISTS idx_fo_status   ON fulfillment_orders (status);
CREATE INDEX IF NOT EXISTS idx_fo_expire  ON fulfillment_orders (expire_at) WHERE expire_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS fulfillment_order_items (
    id                 BIGSERIAL PRIMARY KEY,
    fulfillment_order_id BIGINT    NOT NULL REFERENCES fulfillment_orders (id) ON DELETE CASCADE,
    product_id         BIGINT      NOT NULL REFERENCES products (id),
    asset_id           BIGINT      REFERENCES assets (id),                   -- 产权链回溯（可空）
    device_id          BIGINT      REFERENCES devices (id),                  -- 发货/取货绑定的具体设备（业务粒度）
    qty                INT         NOT NULL DEFAULT 1,
    price              NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (fulfillment_order_id, product_id)
);
CREATE INDEX IF NOT EXISTS idx_foi_asset  ON fulfillment_order_items (asset_id);
CREATE INDEX IF NOT EXISTS idx_foi_device ON fulfillment_order_items (device_id);

CREATE TABLE IF NOT EXISTS fulfillment_settlements (
    id                  BIGSERIAL PRIMARY KEY,
    settlement_no       VARCHAR(40) NOT NULL UNIQUE,
    fulfillment_order_id BIGINT     NOT NULL REFERENCES fulfillment_orders (id),
    manufacturer_id     BIGINT      REFERENCES manufacturers (id),
    station_id          BIGINT      REFERENCES stations (id),
    logistics_fee       NUMERIC(12,2) DEFAULT 0,                            -- 物流费（厂家承担）
    commission_amount   NUMERIC(12,2) DEFAULT 0,                            -- 服务站提成
    balance_to_mfg      NUMERIC(12,2) DEFAULT 0,                            -- 余额归厂家
    step                VARCHAR(20)  NOT NULL DEFAULT 'LOGISTICS',          -- LOGISTICS/COMMISSION/BALANCE（结算进度）
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',            -- PENDING/DONE/MANUAL/FAILED（Q6 挂起）
    ledger_txn_id       VARCHAR(64),                                         -- 复用 ledger 的 txnId
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fs_order ON fulfillment_settlements (fulfillment_order_id);
CREATE INDEX IF NOT EXISTS idx_fs_status ON fulfillment_settlements (status);

-- 事务发件箱（取货扫码领域事件持久化，保证至少一次投递；转发器异步发布到 ApplicationEventPublisher）
CREATE TABLE IF NOT EXISTS outbox_events (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(40) NOT NULL,                 -- 如 FULFILLMENT
    aggregate_id   BIGINT      NOT NULL,                 -- 关联聚合 id（订单 id）
    event_type     VARCHAR(40) NOT NULL,                 -- 如 PickupCompletedEvent
    payload_json   TEXT        NOT NULL,                 -- 事件载荷 JSON
    published      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_ob_unpublished ON outbox_events (published, created_at) WHERE published = FALSE;
