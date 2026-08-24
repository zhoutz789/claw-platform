-- V23：厂家 / 商品 / SKU / 采购订单。闭合「工厂发布商品→SKU定价→客户购买」首步流程。
SET search_path = claw;

CREATE TABLE IF NOT EXISTS manufacturers (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code          VARCHAR(64) NOT NULL UNIQUE,
  name          VARCHAR(160) NOT NULL,
  contact       VARCHAR(160),
  country       VARCHAR(64),
  status        VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS products (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  manufacturer_id BIGINT NOT NULL REFERENCES manufacturers(id),
  name          VARCHAR(160) NOT NULL,
  asset_type    VARCHAR(24) NOT NULL,           -- VEHICLE / BATTERY / CHARGER / SOLAR
  model         VARCHAR(80),
  description   TEXT,
  status        VARCHAR(24) NOT NULL DEFAULT 'ON_SALE',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS product_skus (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  product_id    BIGINT NOT NULL REFERENCES products(id),
  sku_code      VARCHAR(64) NOT NULL UNIQUE,
  price         NUMERIC(18,4) NOT NULL DEFAULT 0,
  currency      VARCHAR(8) NOT NULL DEFAULT 'USD',
  specs_json    TEXT,
  status        VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS purchase_orders (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_no      VARCHAR(64) NOT NULL UNIQUE,
  product_id    BIGINT NOT NULL REFERENCES products(id),
  sku_id        BIGINT NOT NULL REFERENCES product_skus(id),
  buyer_id      BIGINT,
  qty           INT NOT NULL DEFAULT 1,
  unit_price    NUMERIC(18,4) NOT NULL DEFAULT 0,
  total_amount  NUMERIC(18,4) NOT NULL DEFAULT 0,
  currency      VARCHAR(8) NOT NULL DEFAULT 'USD',
  status        VARCHAR(24) NOT NULL DEFAULT 'CREATED',  -- CREATED/PAID/SHIPPED/CANCELLED
  paid_at       TIMESTAMPTZ,
  shipped_at    TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted       BOOLEAN NOT NULL DEFAULT FALSE
);
