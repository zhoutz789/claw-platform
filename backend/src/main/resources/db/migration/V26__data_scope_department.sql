-- V26：数据范围 depARTMENT/TYPE enforcement 落地（C3 遗留迭代）。
-- 1) 部门模型；2) users.department_id；3) 平台固定管理角色数据范围升 ALL。
SET search_path = claw;

CREATE TABLE IF NOT EXISTS departments (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(80) NOT NULL,
  tenant_id   BIGINT NOT NULL DEFAULT 1,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS department_id BIGINT REFERENCES departments(id) ON DELETE SET NULL;

-- 种子：默认总部部门（id=1），供演示与 admin 默认归属。
INSERT INTO departments (id, name, tenant_id) OVERRIDING SYSTEM VALUE VALUES
  (1, '总部', 1)
ON CONFLICT (id) DO NOTHING;

-- 平台固定管理角色（auto_grant=false 的 SUPER_ADMIN/FINANCE_ADMIN/RISK_OFFICER 等）数据范围升 ALL，
-- 否则施加 SELF/DEPARTMENT 会把平台管理员也过滤掉，违背「开箱可用」。
UPDATE roles SET data_scope = 'ALL'
WHERE code IN ('SUPER_ADMIN', 'FINANCE_ADMIN', 'RISK_OFFICER');
