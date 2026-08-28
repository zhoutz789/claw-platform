-- V36：项目管理域（projects / project_devices / device_authorizations）
-- 支撑 Increment 3 C 期（项目管理）：项目自引用树 + 项目-设备绑定 + 转让/共享/授权三态。
-- 风格沿用 V35(claw. 限定) + V29(SET search_path)，遵守 Flyway 纪律（ddl-auto=none，禁止运行时 DDL）。
-- 每项目绑定一个 accounts(account_type='PROJECT') 走 ledger 双记账；不改动 ledger 表结构。
SET search_path = claw;

CREATE TABLE claw.projects (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  name          VARCHAR(120) NOT NULL,
  parent_id     BIGINT REFERENCES claw.projects(id),            -- 子项目自引用树
  depth         INT NOT NULL DEFAULT 0,                         -- 层级深度（根=0）
  sort_no       INT DEFAULT 0,                                  -- 同层自由排序
  account_id    BIGINT REFERENCES claw.accounts(id),            -- 复用 ledger 双记账(每项目一账户)
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  tenant_id     BIGINT NOT NULL DEFAULT 1,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_project_owner ON claw.projects(owner_user_id);
CREATE INDEX idx_project_parent ON claw.projects(parent_id);

CREATE TABLE claw.project_devices (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  BIGINT NOT NULL REFERENCES claw.projects(id),
  asset_id    BIGINT NOT NULL REFERENCES claw.assets(id),
  product_id  BIGINT REFERENCES claw.products(id),              -- 自动 = assets.product_id
  category    VARCHAR(16),
  sort_no     INT DEFAULT 0,
  tenant_id   BIGINT NOT NULL DEFAULT 1,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, asset_id)
);

CREATE TABLE claw.device_authorizations (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id        BIGINT NOT NULL REFERENCES claw.assets(id),
  grantor_user_id BIGINT NOT NULL,   -- 授权人（所有权人）
  grantee_user_id BIGINT,            -- 被授权人（SHARE/AUTHORIZE 填；TRANSFER 置 NULL=进资产大厅）
  auth_type       VARCHAR(16) NOT NULL, -- TRANSFER / SHARE / AUTHORIZE
  scope_json      TEXT,              -- 授予的权限集合(use/locate/revenue/ownership)
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  expires_at      TIMESTAMPTZ,
  tenant_id       BIGINT NOT NULL DEFAULT 1,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_devauth_asset ON claw.device_authorizations(asset_id);
