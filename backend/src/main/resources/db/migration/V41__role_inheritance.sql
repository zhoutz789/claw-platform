-- V41：角色继承（周老板默认决策 #9「角色继承做一层」）。
-- roles 增加 parent_id：逻辑父角色，可空，无 DB 外键；
-- 成环由应用层防御（PermissionService 合并时 visited 集合 + 深度上限）。
-- 幂等：ADD COLUMN IF NOT EXISTS。
SET search_path = claw;

ALTER TABLE roles ADD COLUMN IF NOT EXISTS parent_id BIGINT;
