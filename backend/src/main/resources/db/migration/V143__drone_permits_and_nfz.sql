-- ============================================================================
-- V143 无人机切片 3 —— 合规与运营限制层（schema=claw）
--
-- 依据：《无人机资产接入平台-增量技术设计方案-v3.md》§3.9 / §4（R10 合规与许可，一等公民）
--   drone_airspace_permits        空域许可（SSCA 签发，按资产 + 省域 + 时间窗）
--   drone_mission_permit_bindings 任务/架次 ↔ 许可绑定（"是否强制绑定"为开关，见 V139 permit_gate_mode）
--   nfz_layers                    禁飞图层（多边形 + 时段窗口 + 来源，配置驱动）
--
-- 设计要点：
--   · 许可是「按资产 + 省域 + 时间窗」的一等凭证：PermitGate 校验序
--     ①零容忍区（恒定拒绝）→ ②NFZ 图层（省/时段）→ ③许可有效性与有效期 → ④省域范围。
--   · nfz_layers.polygon_json 统一为 [[lng,lat], ...] 闭合环（GeoJSON ring 风格）；
--     time_window_json 为 {"daily":[{"start":"HH:mm","end":"HH:mm"}]}，**时间一律 UTC**。
--   · permit_no 唯一索引 + 服务层预检双保险（预检给 409 语义，索引兜并发）。
--   · 权限种子三步法（照 V141/V139/V116）：PLATFORM_ADMIN 走 ["*"] 不回写；仅回写 STATION。
-- ⚠️ 幂等：CREATE TABLE / INDEX 全 IF NOT EXISTS；INSERT 全 ON CONFLICT DO NOTHING。
-- ⚠️ 不动 V1–V142；本迁移接在 V142 之后。
-- ============================================================================

SET search_path = claw;

-- ---------- 1) 空域许可 ----------
CREATE TABLE IF NOT EXISTS drone_airspace_permits (
    id             BIGSERIAL   PRIMARY KEY,
    asset_id       BIGINT      NOT NULL,
    permit_no      VARCHAR(64) NOT NULL,
    issuer         VARCHAR(64) NOT NULL DEFAULT 'SSCA',
    scope_province VARCHAR(64),
    valid_from     TIMESTAMPTZ NOT NULL,
    valid_to       TIMESTAMPTZ NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    doc_ref        VARCHAR(128),
    tenant_id      BIGINT      NOT NULL DEFAULT 1,
    deleted        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_dap_permit_no ON drone_airspace_permits (permit_no);
CREATE INDEX IF NOT EXISTS idx_dap_asset_status ON drone_airspace_permits (asset_id, status) WHERE deleted = FALSE;

-- ---------- 2) 任务/架次 ↔ 许可绑定 ----------
CREATE TABLE IF NOT EXISTS drone_mission_permit_bindings (
    id               BIGSERIAL   PRIMARY KEY,
    task_id          BIGINT,
    drone_mission_id BIGINT,
    permit_id        BIGINT      NOT NULL,
    bound_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_id        BIGINT      NOT NULL DEFAULT 1,
    deleted          BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_dmpb_permit FOREIGN KEY (permit_id) REFERENCES drone_airspace_permits (id)
);

CREATE INDEX IF NOT EXISTS idx_dmpb_task   ON drone_mission_permit_bindings (task_id);
CREATE INDEX IF NOT EXISTS idx_dmpb_mission ON drone_mission_permit_bindings (drone_mission_id);
CREATE INDEX IF NOT EXISTS idx_dmpb_permit ON drone_mission_permit_bindings (permit_id);

-- ---------- 3) 禁飞图层 ----------
CREATE TABLE IF NOT EXISTS nfz_layers (
    id               BIGSERIAL   PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    province         VARCHAR(64),
    level            VARCHAR(16) NOT NULL DEFAULT 'ABSOLUTE',
    time_window_json JSONB,
    polygon_json     JSONB,
    source           VARCHAR(32),
    enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_nfz_enabled ON nfz_layers (enabled);
CREATE INDEX IF NOT EXISTS idx_nfz_source  ON nfz_layers (source);

-- ---------- 4) 权限种子（三步法，照 V141/V139） ----------
-- 4.1) 按钮级权限码（读接口 GET 按项目约定不设权限点）
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('drone:permit:manage',     '空域许可签发/吊销', 'BUTTON', 'menu:drone-ops', 8),
  ('drone:nfz:manage',        '禁飞图层维护',      'BUTTON', 'menu:drone-ops', 9),
  ('drone:compliance:config', '合规档位配置',      'BUTTON', 'menu:drone-ops', 10),
  ('drone:camera:register',   '无人机摄像头注册',  'BUTTON', 'menu:drone-ops', 11)
ON CONFLICT (code) DO NOTHING;

-- 4.2) 角色模板挂载
-- PLATFORM_ADMIN 挂模板以保持模板完整（真实权限走 V40 的 ["*"] 通配，第 4.3 步不回写）。
-- drone:nfz:manage / drone:compliance:config 属平台级治理档位，不下放服务站。
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('PLATFORM_ADMIN', 'drone:permit:manage'),
  ('PLATFORM_ADMIN', 'drone:nfz:manage'),
  ('PLATFORM_ADMIN', 'drone:compliance:config'),
  ('PLATFORM_ADMIN', 'drone:camera:register'),
  -- 服务站：日常作业（签发/吊销许可、注册摄像头）
  ('STATION',        'drone:permit:manage'),
  ('STATION',        'drone:camera:register')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 4.3) roles.grants 回写（权限真源）—— 沿用 V141/V139 模式
-- 只回写 STATION（其 grants 与 role_template_permissions 聚合完全一致，回写不丢权限）；
-- PLATFORM_ADMIN 走 ["*"] 通配不在此列（绝不降级）；CUSTOMER 不纳入。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code = 'STATION';
