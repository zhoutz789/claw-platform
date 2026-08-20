-- =====================================================================
-- Claw 平台 V2 增量表（S1：用户权限 / 人人经济角色包 / 资产 ACL / KYC）
-- 依据：《技术开发文档 v0.4》2.2（user_roles / user_assets_acl / kyc_records）
--        + 4.5 人人经济角色模型（RBAC + 角色包 + 资产 ACL 三层权限）
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. RBAC 平台角色分配（domain.role）
--    与 user_role_packages 的区别：这是平台级固定角色（管理员/站方/厂家），
--    由平台/管理员授予；user_role_packages 是用户自驱的动态权限包。
--    两者均引用 roles 角色目录，构成「RBAC + 角色包 + 资产 ACL」三层权限。
-- ---------------------------------------------------------------------
CREATE TABLE claw.user_roles (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES claw.users (id),
    role_id     BIGINT      NOT NULL REFERENCES claw.roles (id),
    granted_by  BIGINT      REFERENCES claw.users (id),   -- 授权人（平台管理员）
    tenant_id   BIGINT      NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, role_id)
);

CREATE INDEX idx_user_roles_user ON claw.user_roles (user_id);

-- ---------------------------------------------------------------------
-- 2. 资产级 ACL（domain.asset）
--    一个用户对某个资产的管理/使用/承租关系，权限即时生效、押金华流转同步失效。
-- ---------------------------------------------------------------------
CREATE TABLE claw.user_assets_acl (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES claw.users (id),
    asset_id    BIGINT      NOT NULL REFERENCES claw.assets (id),
    relation    VARCHAR(16) NOT NULL,                    -- MANAGE 管理 | USE 使用 | LEASE 承租
    granted_by  BIGINT      REFERENCES claw.users (id),
    expired_at  TIMESTAMPTZ,                             -- NULL = 长期有效
    tenant_id   BIGINT      NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, asset_id, relation)
);

CREATE INDEX idx_user_assets_acl_user  ON claw.user_assets_acl (user_id);
CREATE INDEX idx_user_assets_acl_asset ON claw.user_assets_acl (asset_id);

-- ---------------------------------------------------------------------
-- 3. KYC 凭证（domain.user）
--    支持两种认证路径：MANUAL（平台人工/短信实名）与 CAMDIGIKEY（国家数字身份 OAuth2.0 eKYC）。
--    不存储原始证件，仅存 CamDigiKey 返回的授权引用与已授权字段清单。
-- ---------------------------------------------------------------------
CREATE TABLE claw.kyc_records (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES claw.users (id),
    method               VARCHAR(16)  NOT NULL,          -- MANUAL | CAMDIGIKEY
    status               VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING | VERIFIED | REJECTED
    id_type              VARCHAR(16),                    -- NATIONAL_ID | PASSPORT | CAMDIGIKEY
    camdigikey_token_ref VARCHAR(128),                   -- CamDigiKey OAuth2.0 返回的凭证引用
    fields_granted       JSONB,                          -- 已授权获取的字段集合（脱敏）
    consent_version      VARCHAR(32),                    -- 用户同意书版本
    verified_at          TIMESTAMPTZ,
    rejected_reason      VARCHAR(255),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_kyc_user ON claw.kyc_records (user_id, status);
CREATE INDEX idx_kyc_camdigikey ON claw.kyc_records (camdigikey_token_ref) WHERE camdigikey_token_ref IS NOT NULL;

-- ---------------------------------------------------------------------
-- 4. RBAC 平台角色种子（仅固定平台角色，与 V1 的 prosumer 包区分）
--    V1 已种子 CONSUMER..PLATFORM_ADMIN 到 roles 目录；此处仅为语义对齐说明：
--    - 人人经济包（CONSUMER/PRODUCER/DISTRIBUTOR/MERCHANT/OWNER/DRIVER/STATION_OWNER/FLEET_ADMIN）走 user_role_packages
--    - 平台固定角色（PLATFORM_ADMIN）走 user_roles，由管理员授予
-- ---------------------------------------------------------------------
-- 注：roles 目录已在 V1 初始化；本迁移不重复 INSERT，避免唯一冲突。
