-- V39：权限目录增强——permissions 增加 description 列，承载菜单/按钮的说明文案（多语 key 或纯文本）。
-- 纯增量、幂等，不影响已有行与任何既有迁移。
SET search_path = claw;

ALTER TABLE permissions ADD COLUMN IF NOT EXISTS description TEXT;
