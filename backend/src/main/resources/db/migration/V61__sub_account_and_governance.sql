-- =====================================================================
-- Claw 平台 V61 增量（入驻管理 · 增量 C 第三批）
-- 依据：增量设计-入驻管理.md §2.3
-- 范围（4 张新表 + 1 视图 + system_config 种子）：
--   · sub_accounts                子账号（主账号主体 owner，Q11 不允许子账号再开子账号）
--   · sub_account_grants          授权（ALL 跟随模板 / PARTIAL 明细）
--   · sub_account_grant_items     授权明细（仅 PARTIAL 模式，复合主键）
--   · onboarding_org_status_logs  组织状态变更留痕（禁用/启用必填原因 + 二次确认 + 留痕）
--   · v_org_governance            组织治理统一视图（三类主体 UNION，JdbcClient 只读投影，不做 JPA 实体）
--
-- 全量幂等。视图必须先 DROP 再 CREATE（PG 不允许 CREATE OR REPLACE 改列集合）。
-- =====================================================================

SET search_path = claw;

-- ---------- (12) sub_accounts — 子账号 ----------
CREATE TABLE IF NOT EXISTS sub_accounts (
    id                    BIGSERIAL PRIMARY KEY,
    owner_principal_type  VARCHAR(20) NOT NULL,                -- STATION / MANUFACTURER / MERCHANT（主账号主体）
    owner_principal_id    BIGINT      NOT NULL,
    user_id               BIGINT      NOT NULL REFERENCES users (id),
    display_name          VARCHAR(80),
    phone                 VARCHAR(40),
    status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / DISABLED
    disabled_at           TIMESTAMPTZ,
    created_by            BIGINT REFERENCES users (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_principal_type, owner_principal_id, user_id)
);
-- 子账号回溯查询的主入口，必须有
CREATE INDEX IF NOT EXISTS idx_subacct_user ON sub_accounts (user_id);

-- ---------- (13) sub_account_grants — 授权 ----------
-- grant_mode=ALL 的可扩展性核心：不存具体权限码，只存 template_code。
-- 运行时展开 role_template_permissions(template_code)；平台新增功能时只需注册新码并挂到模板，
-- 已授权 ALL 的子账号零改动自动获得。
CREATE TABLE IF NOT EXISTS sub_account_grants (
    id              BIGSERIAL PRIMARY KEY,
    sub_account_id  BIGINT      NOT NULL REFERENCES sub_accounts (id) ON DELETE CASCADE,
    grant_mode      VARCHAR(16) NOT NULL,                      -- ALL / PARTIAL
    template_code   VARCHAR(40),                               -- ALL 模式跟随的角色模板
    granted_by      BIGINT REFERENCES users (id),
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE / REVOKED
    UNIQUE (sub_account_id)
);

-- ---------- (14) sub_account_grant_items — 授权明细（仅 PARTIAL 模式） ----------
CREATE TABLE IF NOT EXISTS sub_account_grant_items (
    grant_id         BIGINT      NOT NULL REFERENCES sub_account_grants (id) ON DELETE CASCADE,
    permission_code  VARCHAR(80) NOT NULL REFERENCES permissions (code),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (grant_id, permission_code)
);

-- ---------- (15) onboarding_org_status_logs — 组织状态变更留痕 ----------
CREATE TABLE IF NOT EXISTS onboarding_org_status_logs (
    id              BIGSERIAL PRIMARY KEY,
    principal_type  VARCHAR(20) NOT NULL,
    principal_id    BIGINT      NOT NULL,
    from_status     VARCHAR(24),
    to_status       VARCHAR(24) NOT NULL,                      -- ACTIVE / DISABLED / REJECTED
    action          VARCHAR(24) NOT NULL,                      -- ACTIVATE / DISABLE / ENABLE / REJECT
    reason          TEXT,                                      -- 禁用原因（必填）
    operator_id     BIGINT REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_orglog_principal
    ON onboarding_org_status_logs (principal_type, principal_id, created_at DESC);

-- ---------- (16) v_org_governance — 组织治理统一视图 ----------
-- 三类主体 UNION，无单一物理主键；不做 JPA 实体映射，统一用 Spring JdbcClient 只读投影。
DROP VIEW IF EXISTS v_org_governance;
CREATE VIEW v_org_governance AS
SELECT 'STATION'      AS principal_type, id AS principal_id, code, name,
       onboarding_status, deposit_tier_id, credit_limit,
       onboarding_application_id, disabled_at, disabled_reason, disabled_by
  FROM stations      WHERE deleted = FALSE
UNION ALL
SELECT 'MANUFACTURER', id, code, name,
       onboarding_status, deposit_tier_id, credit_limit,
       onboarding_application_id, disabled_at, disabled_reason, disabled_by
  FROM manufacturers WHERE deleted = FALSE
UNION ALL
SELECT 'MERCHANT',     id, code, name,
       onboarding_status, deposit_tier_id, credit_limit,
       onboarding_application_id, disabled_at, disabled_reason, disabled_by
  FROM merchants     WHERE deleted = FALSE;

-- ---------- system_config 种子（入驻域可配参数） ----------
INSERT INTO system_config (config_key, config_value, category, description, data_type, editable) VALUES
    ('ONBOARDING_DEPOSIT_PAY_DAYS',              '15',   'ONBOARDING', '保证金缴款超时天数（超时自动转 EXPIRED）', 'NUMBER',  TRUE),
    ('ONBOARDING_DRAFT_KEEP_DAYS',               '30',   'ONBOARDING', '入驻申请草稿保留天数',                      'NUMBER',  TRUE),
    ('ONBOARDING_CREDIT_MULTIPLIER_DEFAULT',     '3',    'ONBOARDING', '新建保证金档位时授信倍率的默认值（额度 = 保证金 × 倍率）', 'NUMBER', TRUE),
    ('ONBOARDING_CREDIT_SCOPE',                  'STATION', 'ONBOARDING', '授信额度口径：STATION 站级总额 / MFG_STATION 厂家逐对', 'STRING', TRUE),
    ('ONBOARDING_SIGNBOARD_REVIEW_REQUIRED',     'true', 'ONBOARDING', '门头照片是否需平台审核通过后才对外展示',    'BOOLEAN', TRUE),
    ('ONBOARDING_CREDIT_WARN_RATIO',             '0.8',  'ONBOARDING', '授信占用达该比例时告警（0–1）',             'NUMBER',  TRUE)
ON CONFLICT (config_key) DO NOTHING;
