-- ============================================================================
-- V137 无人机机型产品类配置模型（schema=claw）—— 切片 1
-- 纯配置驱动多机型：新增第 N 种机型 = 插 N 行配置，零代码。
-- 镜像 V122__vehicle_product_class.sql；capability_tags / default_device_types /
--   required_certs 用 TEXT(CSV) 规避 text[] 数组映射依赖。
-- 名称三语：name_zh / name_en / name_km（直接落列，不依赖独立 i18n 查找表）。
-- 新增 ops_template(JSONB) 作业模版 + dock_supported(是否适配自动机场)。
-- 不动 V1–V136；本迁移接在 V136 之后。
-- ============================================================================

CREATE TABLE IF NOT EXISTS claw.drone_product_classes (
    id                   BIGSERIAL     PRIMARY KEY,
    code                 VARCHAR(64)   NOT NULL UNIQUE,   -- 稳定代码标识（非 i18n，作 FK/引用键）
    name_zh              VARCHAR(128)  NOT NULL,
    name_en              VARCHAR(128)  NOT NULL,
    name_km              VARCHAR(128)  NOT NULL,
    scenario             VARCHAR(32)   NOT NULL,          -- AGRICULTURE / INSPECTION / LOGISTICS / RESCUE / SURVEY / PATROL / MONITOR
    capability_tags      TEXT,                            -- CSV: AGRICULTURE,MONITOR
    default_device_types TEXT,                            -- CSV: DRONE_FCU,CAMERA
    attr_schema          JSONB,                           -- 结构化属性 schema（可选）
    required_certs       TEXT,                            -- CSV: SSCA适航证,飞行许可
    ops_template         JSONB,                           -- 作业模版（SPRAY/CARGO/INSPECTION/RESCUE ...）
    dock_supported       BOOLEAN       NOT NULL DEFAULT false,  -- 是否适配 DJI Dock 3 自动机场
    enabled              BOOLEAN       NOT NULL DEFAULT true,
    created_at           TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS claw.drone_scenario_attrs (
    id               BIGSERIAL     PRIMARY KEY,
    product_class_id BIGINT        NOT NULL REFERENCES claw.drone_product_classes (id),
    attr_key         VARCHAR(64)   NOT NULL,
    attr_type        VARCHAR(32)   NOT NULL,             -- STRING / NUMBER / ENUM / DATE
    unit             VARCHAR(16),
    is_required      BOOLEAN       NOT NULL DEFAULT FALSE,
    label_zh         VARCHAR(128)  NOT NULL DEFAULT '',
    label_en         VARCHAR(128)  NOT NULL DEFAULT '',
    label_km         VARCHAR(128)  NOT NULL DEFAULT '',
    sort_order       INT           NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_drone_product_classes_scenario ON claw.drone_product_classes (scenario);
CREATE INDEX IF NOT EXISTS idx_drone_scenario_attrs_class    ON claw.drone_scenario_attrs (product_class_id);
