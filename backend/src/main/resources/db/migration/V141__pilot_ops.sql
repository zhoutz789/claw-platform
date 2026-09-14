-- ============================================================================
-- V141 无人机切片 2 —— 飞手运营域（审核 + 行为监控 + 违规处罚历史）+ 权限种子
--
-- 依据：《无人机资产接入平台-增量技术设计方案-v3.md》§3.5 / §4（R5）
--   pilot_profile       飞手↔用户绑定（补 pilot_licenses 无 user_id 的关键缺口，决策 D5）
--   pilot_behavior_event 行为事件（由遥测派生：越界/超载/失联/超时/危险操作）
--   pilot_penalty        违规处罚历史（WARN/FINE/SUSPEND/REVOKE + 分值）
--
-- ⚠️ pilot_licenses 无 user_id（V29 遗留），故 pilot_profile.user_id 独承担「飞手↔平台用户」
--    绑定；license_no 仅作弱引用（可为空：未持证飞手也可先建档，审核通过时再补）。
--    license_no 上 NOT 加外键：执照登记与飞手建档生命周期不同，且历史执照可续期换号。
-- ⚠️ pilot_penalty.pilot_id 指向 pilot_profile.id（非 user_id），保证处罚历史随档案聚合。
-- ⚠️ 幂等：CREATE TABLE / INDEX 全 IF NOT EXISTS；INSERT 全 ON CONFLICT DO NOTHING；
--    UPDATE 按 code 限定。二次执行无副作用。
-- ⚠️ 权限回写铁律（照 V139/V81）：PLATFORM_ADMIN 走 V40 的 ["*"] 通配，**绝不回写**（会降级为白名单）；
--    本次仅回写 STATION / PILOT（二者 grants 与其 role_template_permissions 聚合完全一致，无孤儿权限）。
-- ⚠️ 不动 V1–V140；本迁移接在 V140 之后。
-- ============================================================================

SET search_path = claw;

-- ---------- 1) 飞手档案 ----------
CREATE TABLE IF NOT EXISTS pilot_profile (
    id           BIGSERIAL   PRIMARY KEY,
    user_id      BIGINT      NOT NULL UNIQUE,
    license_no   VARCHAR(64),
    kyc_level    VARCHAR(16) NOT NULL DEFAULT 'BASIC',
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    level        INT         NOT NULL DEFAULT 1,
    credit_score INT         NOT NULL DEFAULT 100,
    approved_by  BIGINT,
    approved_at  TIMESTAMPTZ,
    tenant_id    BIGINT      NOT NULL DEFAULT 1,
    deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pilot_profile_status ON pilot_profile (status) WHERE deleted = FALSE;

-- ---------- 2) 飞手行为事件（遥测派生，只记录不直接处罚） ----------
CREATE TABLE IF NOT EXISTS pilot_behavior_event (
    id            BIGSERIAL   PRIMARY KEY,
    pilot_user_id BIGINT      NOT NULL,
    asset_id      BIGINT,
    event_type    VARCHAR(32) NOT NULL,
    severity      VARCHAR(16) NOT NULL DEFAULT 'LOW',
    detail_json   JSONB,
    source_ref    VARCHAR(64),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pbe_pilot_time ON pilot_behavior_event (pilot_user_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_pbe_asset      ON pilot_behavior_event (asset_id);

-- ---------- 3) 违规处罚历史 ----------
CREATE TABLE IF NOT EXISTS pilot_penalty (
    id             BIGSERIAL   PRIMARY KEY,
    pilot_id       BIGINT      NOT NULL,
    cause          VARCHAR(64) NOT NULL,
    severity       VARCHAR(16) NOT NULL DEFAULT 'LOW',
    points         INT         NOT NULL DEFAULT 0,
    penalty_type   VARCHAR(16) NOT NULL,
    biz_ref        VARCHAR(64),
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    effective_from TIMESTAMPTZ,
    effective_to   TIMESTAMPTZ,
    decided_by     BIGINT,
    decided_at     TIMESTAMPTZ DEFAULT now(),
    tenant_id      BIGINT      NOT NULL DEFAULT 1,
    deleted        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_pp_profile FOREIGN KEY (pilot_id) REFERENCES pilot_profile (id)
);

CREATE INDEX IF NOT EXISTS idx_pp_pilot ON pilot_penalty (pilot_id, decided_at DESC) WHERE deleted = FALSE;

-- ---------- 4) 权限种子（三步法，照 V139/V116） ----------
-- 4.1) 按钮级权限码（均挂 menu:drone-ops；读接口 GET 按项目约定不设权限点）
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('drone:battery:bind',   '绑定/解绑无人机电池', 'BUTTON', 'menu:drone-ops', 4),
  ('drone:pilot:manage',   '登记飞手档案',       'BUTTON', 'menu:drone-ops', 5),
  ('drone:pilot:approve',  '审核飞手档案',       'BUTTON', 'menu:drone-ops', 6),
  ('drone:pilot:penalize', '飞手违规处罚',       'BUTTON', 'menu:drone-ops', 7)
ON CONFLICT (code) DO NOTHING;

-- 4.2) 角色模板挂载
-- PLATFORM_ADMIN 挂模板以保持模板完整（其真实权限走 V40 的 ["*"] 通配，第 4.3 步不回写）。
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('PLATFORM_ADMIN', 'drone:battery:bind'),
  ('PLATFORM_ADMIN', 'drone:pilot:manage'),
  ('PLATFORM_ADMIN', 'drone:pilot:approve'),
  ('PLATFORM_ADMIN', 'drone:pilot:penalize'),
  -- 服务站：换电作业绑定 + 飞手建档/审核/处罚
  ('STATION',        'drone:battery:bind'),
  ('STATION',        'drone:pilot:manage'),
  ('STATION',        'drone:pilot:approve'),
  ('STATION',        'drone:pilot:penalize'),
  -- 飞手：自助建档（只给 manage，审核与处罚仍在服务站/平台侧）
  ('PILOT',          'drone:pilot:manage')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- 4.3) roles.grants 回写（权限真源）—— 沿用 V139/V66/V116 模式
-- 只回写 STATION / PILOT（其 grants 与 role_template_permissions 聚合完全一致，回写不丢权限）；
-- PLATFORM_ADMIN 走 ["*"] 通配不在此列（绝不降级）；CUSTOMER 不纳入（勿动对象/白名单结构）。
-- roles.grants 为 TEXT，回写须 ::text。
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('STATION', 'PILOT');
