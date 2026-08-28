-- V35：产品模板字段 EAV + 产品表扩展(report_interval_seconds / attr_json) + 通用电子围栏
-- 支撑 Increment 3 A 期（数据底座）：产品目录 admin CRUD、模板字段可扩展、位置/围栏。
-- 风格沿用 V33(claw. 限定) + V29(SET search_path)，遵守 Flyway 纪律（ddl-auto=none，禁止运行时 DDL）。
SET search_path = claw;

-- 模版字段 EAV（明确否决"每模版单独建表"，遵守 Flyway 纪律 + JPA 静态映射）。
-- 产品实例的扩展属性值存 products.params_json / products.attr_json，不另建表。
CREATE TABLE claw.product_template_fields (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  product_id  BIGINT NOT NULL REFERENCES claw.products(id),
  field_key   VARCHAR(64) NOT NULL,
  label       VARCHAR(120) NOT NULL,
  type        VARCHAR(16) NOT NULL,   -- number/text/select/date/boolean
  unit        VARCHAR(16),
  options_json TEXT,                   -- select 选项(JSON 数组)
  required    BOOLEAN NOT NULL DEFAULT false,
  sort_no     INT DEFAULT 0,
  tenant_id   BIGINT NOT NULL DEFAULT 1,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (product_id, field_key)
);

-- 产品表扩展（点 2 扩展属性值 + 点 3 上报间隔，每产品时序要求不同）。
ALTER TABLE claw.products
  ADD COLUMN report_interval_seconds INT,    -- 每产品上报间隔(秒)
  ADD COLUMN attr_json TEXT;                 -- 扩展属性值(或复用 params_json)

-- 通用电子围栏（几何照搬 V29 airspace_zones，去无人机专用化，支持全实体类型）。
CREATE TABLE claw.geofences (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  owner_type    VARCHAR(16) NOT NULL,        -- PRODUCT/DEVICE/ASSET/PROJECT
  owner_id      BIGINT NOT NULL,
  fence_type    VARCHAR(16) NOT NULL,        -- RADIUS/POLYGON
  center_lat    NUMERIC(10,7),
  center_lng    NUMERIC(10,7),
  radius_m      INT,
  polygon_wkt   TEXT,                         -- POLYGON 类型填(WKT: POLYGON((lng lat, ...)))
  trigger_action VARCHAR(16) NOT NULL DEFAULT 'ALERT', -- ENTER/EXIT/INTRUSION(→ALERT/LOCK)
  status        VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  tenant_id     BIGINT NOT NULL DEFAULT 1,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_geofence_owner ON claw.geofences(owner_type, owner_id);
