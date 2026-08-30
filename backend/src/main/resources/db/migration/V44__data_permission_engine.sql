-- V44：数据权限 JPA 引擎（P1-T03）。
-- 把"只被 2 处硬编码调用的数据范围"升级为可配置、JPA Specification 原生、对未配置角色保持原行为的数据权限引擎。
-- 1) permission_data_rules：可配置数据规则（列/条件/值 + #{...} 上下文变量）；
-- 2) role_permissions.data_rule_ids：角色-权限绑定的数据规则；
-- 3) roles.data_rule_ids：角色级自定义部门集合（CUSTOM 范围使用）。
-- 全量幂等：CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS；种子用唯一约束去重。
SET search_path = claw;

CREATE TABLE IF NOT EXISTS permission_data_rules (
    id              BIGSERIAL PRIMARY KEY,
    permission_code VARCHAR(120) NOT NULL,
    rule_name       VARCHAR(120) NOT NULL,
    rule_column     VARCHAR(120) NOT NULL,
    rule_conditions VARCHAR(16)  NOT NULL DEFAULT '=',
    rule_value      TEXT,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_permission_data_rules UNIQUE (permission_code, rule_name)
);

ALTER TABLE role_permissions ADD COLUMN IF NOT EXISTS data_rule_ids TEXT;
ALTER TABLE roles ADD COLUMN IF NOT EXISTS data_rule_ids TEXT;
