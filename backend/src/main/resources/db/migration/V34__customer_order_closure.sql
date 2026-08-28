-- V34：客户订单闭环（Increment 2）
-- 客户订单 / 订单项 / 阶梯押金规则(+种子) / 合格证(自建表，含 order_id)。
-- 不改动 V1–V33 任何对象；certificates 此前从未被任何迁移创建，此处 CREATE。

CREATE TABLE claw.customer_orders (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_no       VARCHAR(40)  NOT NULL UNIQUE,
  buyer_user_id  BIGINT       NOT NULL,
  product_id     BIGINT,
  asset_id       BIGINT       REFERENCES claw.assets(id),
  asset_type     VARCHAR(16),
  usage_mode     VARCHAR(12)  NOT NULL DEFAULT 'SELF',
  status         VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
  total_amount   NUMERIC(18,4) NOT NULL DEFAULT 0,
  deposit_amount NUMERIC(18,4) NOT NULL DEFAULT 0,
  deposit_no     VARCHAR(40),
  pay_order_no   VARCHAR(40),
  station_id     BIGINT,
  pool_entry_id  BIGINT,
  split_rule_id  BIGINT,
  certificate_id BIGINT,
  shipped_at     TIMESTAMPTZ,
  completed_at   TIMESTAMPTZ,
  cancel_reason  VARCHAR(255),
  refund_status  VARCHAR(16),
  tenant_id      BIGINT       NOT NULL DEFAULT 1,
  deleted        BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_orders_buyer ON claw.customer_orders(buyer_user_id);

CREATE TABLE claw.customer_order_items (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id   BIGINT       NOT NULL REFERENCES claw.customer_orders(id),
  asset_id   BIGINT       REFERENCES claw.assets(id),
  sku_id     BIGINT,
  asset_type VARCHAR(16),
  quantity   INT          NOT NULL DEFAULT 1,
  unit_price NUMERIC(18,4) NOT NULL DEFAULT 0,
  subtotal   NUMERIC(18,4) NOT NULL DEFAULT 0,
  tenant_id  BIGINT       NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_items_order ON claw.customer_order_items(order_id);

-- R4 阶梯押金规则（车辆/电池同比例，PRD 4.16）
CREATE TABLE claw.deposit_rules (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_type   VARCHAR(16) NOT NULL,
  year_index   INT         NOT NULL,
  deposit_rate NUMERIC(6,4) NOT NULL,
  min_rate     NUMERIC(6,4) NOT NULL DEFAULT 0.15,
  tenant_id    BIGINT NOT NULL DEFAULT 1,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (asset_type, year_index)
);
INSERT INTO claw.deposit_rules(asset_type, year_index, deposit_rate, min_rate) VALUES
  ('EV',1,0.30,0.15),('EV',2,0.25,0.15),('EV',3,0.20,0.15),('EV',4,0.15,0.15),
  ('BATTERY',1,0.30,0.15),('BATTERY',2,0.25,0.15),('BATTERY',3,0.20,0.15),('BATTERY',4,0.15,0.15);

-- 合格证表：本期自建（product-link 增量 V27 未落地）。
-- product_link_id 不建外键（product_links 表可能不存在），保持可空 BIGINT。
CREATE TABLE claw.certificates (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  cert_type        VARCHAR(20)  NOT NULL,
  cert_no          VARCHAR(40)  NOT NULL UNIQUE,
  product_link_id  BIGINT,
  asset_id         BIGINT,
  data_json        TEXT,
  template_version VARCHAR(20),
  issued_by        VARCHAR(60),
  issued_at        TIMESTAMPTZ,
  file_url         VARCHAR(500),
  order_id         BIGINT,
  tenant_id        BIGINT       NOT NULL DEFAULT 1,
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_certificates_asset ON claw.certificates(asset_id);
CREATE INDEX idx_certificates_order ON claw.certificates(order_id);
