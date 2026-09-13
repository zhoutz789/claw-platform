-- ============================================================================
-- V127 账号密码登录：users 表新增 password_hash 列（BCrypt 哈希，可空）
-- 仅新增列，绝不修改 V1–V126。
-- ============================================================================

SET search_path = claw;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
