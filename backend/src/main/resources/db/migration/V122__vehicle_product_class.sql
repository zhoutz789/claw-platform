-- ============================================================================
-- V122 车辆产品类配置模型（schema=claw）
-- 纯配置驱动多车型：新增第 N 种车型 = 插 N 行配置，零代码。
-- capability_tags / default_device_types / required_certs 用 TEXT(CSV) 规避 text[]
--   数组映射依赖（与 V120/V121 一致）。
-- 名称三语：name_zh / name_en / name_km（不依赖独立 i18n 查找表，直接落列）。
-- 不动 V1–V121；本迁移接在 V121 之后。
-- ============================================================================

CREATE TABLE claw.vehicle_product_classes (
    id                  BIGSERIAL     PRIMARY KEY,
    code                VARCHAR(64)   NOT NULL UNIQUE,   -- 稳定代码标识（非 i18n，作 FK/引用键）
    name_zh             VARCHAR(128)  NOT NULL,
    name_en             VARCHAR(128)  NOT NULL,
    name_km             VARCHAR(128)  NOT NULL,
    scenario            VARCHAR(32)   NOT NULL,          -- LOGISTICS / RIDE_HAIL / SANITATION / EMERGENCY / ...
    capability_tags     TEXT,                             -- CSV: LOGISTICS,RIDE_HAIL,AD_DISPLAY
    default_device_types TEXT,                           -- CSV: VEHICLE_TCU,BMS,CAMERA,AD_SCREEN
    attr_schema         JSONB,                           -- 结构化属性 schema（可选）
    required_certs      TEXT,                            -- CSV: 营运证,合格证,一致性证书
    geofence_preset     JSONB,                           -- 场景默认围栏/路网模板
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE TABLE claw.vehicle_scenario_attrs (
    id               BIGSERIAL     PRIMARY KEY,
    product_class_id BIGINT        NOT NULL REFERENCES claw.vehicle_product_classes (id),
    attr_key         VARCHAR(64)   NOT NULL,
    attr_type        VARCHAR(32)   NOT NULL,             -- STRING / NUMBER / ENUM / DATE
    unit             VARCHAR(16),
    is_required      BOOLEAN       NOT NULL DEFAULT FALSE,
    label_zh         VARCHAR(128)  NOT NULL DEFAULT '',
    label_en         VARCHAR(128)  NOT NULL DEFAULT '',
    label_km         VARCHAR(128)  NOT NULL DEFAULT '',
    sort_order       INT           NOT NULL DEFAULT 0
);

CREATE INDEX idx_vehicle_product_classes_scenario ON claw.vehicle_product_classes (scenario);
CREATE INDEX idx_vehicle_scenario_attrs_class    ON claw.vehicle_scenario_attrs (product_class_id);
