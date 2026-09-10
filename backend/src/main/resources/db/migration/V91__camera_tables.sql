-- =====================================================================
-- Claw 平台 V91 增量：摄像头 / 录像子系统表
--
-- 对应《摄像头数据子系统技术方案 v1》《摄像头子模块增量实现计划》：
-- 摄像头作为资产子实体（camera_stream），视频段/事件片段/稀疏帧/白名单/留存策略。
-- 视频像素不出站，本表仅存索引与元数据；实际视频由边缘媒体节点承载。
--
-- ⚠️ 幂等：全部 IF NOT EXISTS，二次执行无副作用。
-- ⚠️ 顶部 SET search_path = claw，表名严禁加 "claw." 前缀。
-- =====================================================================

SET search_path = claw;

CREATE TABLE IF NOT EXISTS camera_stream (
  id           BIGSERIAL PRIMARY KEY,
  asset_id     BIGINT,
  camera_idx   INT,
  name         VARCHAR(128),
  protocol     VARCHAR(16),
  resolution   VARCHAR(16),
  status       VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
  stream_url   TEXT,
  last_heartbeat TIMESTAMPTZ,
  tenant_id    BIGINT NOT NULL DEFAULT 1,
  deleted      BOOLEAN NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS video_segment (
  id          BIGSERIAL PRIMARY KEY,
  stream_id   BIGINT NOT NULL,
  start_ts    TIMESTAMPTZ NOT NULL,
  end_ts      TIMESTAMPTZ NOT NULL,
  tier        SMALLINT NOT NULL DEFAULT 1,
  object_key  TEXT,
  size_bytes  BIGINT,
  event_tag   VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS event_clip (
  id          BIGSERIAL PRIMARY KEY,
  stream_id   BIGINT NOT NULL,
  start_ts    TIMESTAMPTZ NOT NULL,
  end_ts      TIMESTAMPTZ NOT NULL,
  type        VARCHAR(32),
  object_key  TEXT,
  permanent   BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sparse_frame (
  id         BIGSERIAL PRIMARY KEY,
  stream_id  BIGINT NOT NULL,
  ts         TIMESTAMPTZ NOT NULL,
  thumb_key  TEXT
);

CREATE TABLE IF NOT EXISTS monitor_whitelist (
  asset_id       BIGINT PRIMARY KEY,
  raw_permanent  BOOLEAN NOT NULL DEFAULT TRUE,
  window_days    INT NOT NULL DEFAULT 365,
  reason         VARCHAR(128),
  granted_by     BIGINT,
  granted_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS retention_policy (
  asset_class         VARCHAR(32) PRIMARY KEY,
  raw_window_days     INT NOT NULL DEFAULT 30,
  sparse_interval_sec INT NOT NULL DEFAULT 600,
  event_permanent     BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_camera_asset ON camera_stream (asset_id);
CREATE INDEX IF NOT EXISTS idx_segment_stream_time ON video_segment (stream_id, start_ts);
CREATE INDEX IF NOT EXISTS idx_event_stream_time ON event_clip (stream_id, start_ts);
CREATE INDEX IF NOT EXISTS idx_sparse_stream_time ON sparse_frame (stream_id, ts);
