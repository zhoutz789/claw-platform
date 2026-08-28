-- ============ V38：订单 → 资产生成与流转（SKU↔资产，发货前逐台登记）============
-- 接续磁盘最新迁移 V37（project_ledger_master_offset）。本增量不改动 V1–V37 任何对象。
-- 仅新增「逐台登记台账」+ 给 assets / asset_maintenance_records 加列。
-- 不在 customer_orders 加 vin/motor_no/frame_no（旧 V37 片段已被本方案取代，见设计 §0.3）。

-- ============ 1) 逐台登记台账：1 SKU → N 资产 的权威记录 ============
CREATE TABLE claw.customer_order_unit_registrations (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id         BIGINT NOT NULL REFERENCES claw.customer_orders(id),
  order_item_id    BIGINT NOT NULL REFERENCES claw.customer_order_items(id),
  seq              INT    NOT NULL,                 -- 该 SKU 内第几台（1..quantity）
  qr_code          VARCHAR(128) NOT NULL,           -- 逐台唯一二维码
  vin              VARCHAR(64),                     -- 车架号（车辆）
  frame_no         VARCHAR(64),                     -- 车架号(主部件)
  motor_no         VARCHAR(64),                     -- 电机号
  component_nos_json TEXT,                          -- 主要元件编号集合（JSON，见设计 §2.4）
  asset_id         BIGINT NOT NULL REFERENCES claw.assets(id),
  status           VARCHAR(16) NOT NULL DEFAULT 'REGISTERED', -- DRAFT/REGISTERED/CONFIRMED
  tenant_id        BIGINT NOT NULL DEFAULT 1,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (order_item_id, seq),
  UNIQUE (qr_code),
  UNIQUE (asset_id)
);
CREATE INDEX idx_unitreg_order ON claw.customer_order_unit_registrations(order_id);
CREATE INDEX idx_unitreg_item  ON claw.customer_order_unit_registrations(order_item_id);

-- ============ 2) assets：资产溯源 + 当前主部件快照 ============
ALTER TABLE claw.assets
  ADD COLUMN order_item_id    BIGINT REFERENCES claw.customer_order_items(id),
  ADD COLUMN component_nos_json TEXT;               -- 当前主部件编号快照（变更见设计 §2.4）
CREATE INDEX idx_asset_order_item ON claw.assets(order_item_id);

-- ============ 3) 维修更换留痕（F7.4 / F16.5）============
ALTER TABLE claw.asset_maintenance_records
  ADD COLUMN component_type    VARCHAR(32),         -- MOTOR/BATTERY/CONTROLLER/REMOTE/CHARGER...
  ADD COLUMN old_component_no  VARCHAR(64),
  ADD COLUMN new_component_no  VARCHAR(64);
