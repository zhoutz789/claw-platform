-- V42：部门层级（C3 数据范围维度锚点扩展）。
-- departments 增加 parent_id（可空，树根 parent_id = NULL）+ org_code（邮编式层级码，如 'A01' / 'A01B02'）。
-- 无 DB 外键；层级组装由 AdminDepartmentController 在内存中按 parent_id 完成。
-- 幂等：ADD COLUMN IF NOT EXISTS。
SET search_path = claw;

ALTER TABLE departments ADD COLUMN IF NOT EXISTS parent_id BIGINT;
ALTER TABLE departments ADD COLUMN IF NOT EXISTS org_code VARCHAR(32);
