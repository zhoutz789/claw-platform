-- ============================================================================
-- V121 无人车 autonomy 子域 6 表（schema=claw，前缀 autonomy_）
-- 仅新建，绝不修改 V1–V119。无人车复用 VEHICLE 资产类，
-- 本域仅表达"自主执行"差异（执行主体从人变算法，共享道路/换电/保险/产权链全部复用）。
-- 算力归属平台（云端协同 V2X / cloud inference）；自主任务收益分成见 revenue_split_rules 新增 AUTONOMY 规则。
-- ============================================================================

CREATE TABLE claw.autonomy_modules (
    id                  BIGSERIAL      PRIMARY KEY,
    asset_id            BIGINT         NOT NULL,
    algo_version        VARCHAR(32)    NOT NULL,
    firmware_version    VARCHAR(32),
    learning_model_id   BIGINT,
    drive_mode          VARCHAR(16)    NOT NULL DEFAULT 'ASSISTED', -- ASSISTED / TELEOP / FULL
    safety_state        VARCHAR(16)    NOT NULL DEFAULT 'NORMAL',  -- NORMAL / DEGRADED / LOCKED
    created_at          TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE TABLE claw.autonomy_tasks (
    id                  BIGSERIAL      PRIMARY KEY,
    task_id             BIGINT,                            -- 关联 tasks.id（TaskType.AUTO_*）
    asset_id            BIGINT         NOT NULL,
    subtype             VARCHAR(32)    NOT NULL,             -- DELIVERY / SWEEP / PATROL
    status              VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    path_json           JSONB,                             -- 路径点序列
    current_waypoint    INT,
    progress_pct        INT            NOT NULL DEFAULT 0,
    created_at          TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE TABLE claw.autonomy_safety_events (
    id                  BIGSERIAL      PRIMARY KEY,
    asset_id            BIGINT         NOT NULL,
    cause               VARCHAR(32)    NOT NULL,             -- GEOFENCE / LOST_LINK / LOW_BATTERY / OBSTACLE / E_STOP
    severity            VARCHAR(16)    NOT NULL DEFAULT 'WARN', -- WARN / CRITICAL
    detail_json         JSONB,
    created_at          TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE TABLE claw.learning_models (
    id                      BIGSERIAL      PRIMARY KEY,
    name                    VARCHAR(64)    NOT NULL,
    version                 VARCHAR(32)    NOT NULL,
    trained_on_dataset_ref  TEXT,
    accuracy                NUMERIC(6,4),
    shared                  BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE TABLE claw.voice_interactions (
    id                  BIGSERIAL      PRIMARY KEY,
    asset_id            BIGINT         NOT NULL,
    direction           VARCHAR(8)     NOT NULL,            -- IN / OUT
    text                TEXT           NOT NULL,
    lang                VARCHAR(8)     NOT NULL DEFAULT 'km',
    created_at          TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE TABLE claw.ground_geofences (
    id                  BIGSERIAL      PRIMARY KEY,
    name                VARCHAR(128)   NOT NULL,
    level               VARCHAR(16)    NOT NULL,            -- WORK / NO_GO / AVOID
    polygon_json        JSONB,
    created_at          TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE INDEX idx_autonomy_modules_asset       ON claw.autonomy_modules (asset_id);
CREATE INDEX idx_autonomy_tasks_asset         ON claw.autonomy_tasks (asset_id);
CREATE INDEX idx_autonomy_tasks_status        ON claw.autonomy_tasks (status);
CREATE INDEX idx_autonomy_safety_events_asset ON claw.autonomy_safety_events (asset_id);
CREATE INDEX idx_voice_interactions_asset     ON claw.voice_interactions (asset_id);
CREATE INDEX idx_ground_geofences_level       ON claw.ground_geofences (level);
