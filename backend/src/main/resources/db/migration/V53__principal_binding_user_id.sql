-- =====================================================================
-- Claw 平台 V53 增量（修复 principal_bindings 主体列与实体不一致）
--
-- 背景（缺陷）：V47 建表用了
--     account_id BIGINT NOT NULL REFERENCES accounts(id)
-- 但被实际使用的实体 domain/role/PrincipalBinding.java 映射的是 user_id，
-- 且 PrincipalBindingService / FulfillmentService 里 b.getUserId() 取的是
-- **登录用户 ID**。而 claw.accounts 是 V1 建的**复式记账账户表**（其 user_id
-- 列才指向 users），登录账号表是 claw.users —— 外键指错域，会 ID 串域。
--
-- 修复：
--   1) 新增 user_id BIGINT
--   2) 迁移既有数据：principal_bindings.account_id -> accounts.user_id
--   3) 清掉无法映射的历史脏数据，保证 user_id 可加 NOT NULL
--   4) 删旧约束（UNIQUE(account_id, principal_type) + account_id 外键）并 DROP account_id
--   5) 建 UNIQUE(user_id, principal_type) 与 user_id -> users(id) 外键
--
-- 全量幂等：ADD COLUMN IF NOT EXISTS / DROP ... IF EXISTS / CREATE UNIQUE INDEX IF NOT EXISTS
--          / DO 块内按 pg_constraint 判断后再建外键。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 新增 user_id 列（先可空，迁移后再收紧为 NOT NULL）----------
ALTER TABLE principal_bindings ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- ---------- 2) 迁移既有数据：account_id（记账账户）-> accounts.user_id（登录用户）----------
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'claw'
          AND table_name   = 'principal_bindings'
          AND column_name  = 'account_id'
    ) THEN
        UPDATE claw.principal_bindings pb
           SET user_id = a.user_id
          FROM claw.accounts a
         WHERE a.id = pb.account_id
           AND pb.user_id IS NULL;
    END IF;
END $$;

-- ---------- 3) 无法映射的历史脏数据（account_id 在 accounts 中无对应 user_id）----------
-- user_id 为 NOT NULL，无法回填的绑定行只能删除（绑定可重建，ID 串域更危险）。
DELETE FROM claw.principal_bindings WHERE user_id IS NULL;

-- ---------- 4) 删除旧约束与 account_id 列 ----------
-- V47 内联 UNIQUE(account_id, principal_type) 的 PG 自动命名
ALTER TABLE principal_bindings DROP CONSTRAINT IF EXISTS principal_bindings_account_id_principal_type_key;
-- V47 列级 REFERENCES accounts(id) 的 PG 自动命名
ALTER TABLE principal_bindings DROP CONSTRAINT IF EXISTS principal_bindings_account_id_fkey;
ALTER TABLE principal_bindings DROP COLUMN IF EXISTS account_id;

-- ---------- 5) user_id 收紧为 NOT NULL ----------
ALTER TABLE principal_bindings ALTER COLUMN user_id SET NOT NULL;

-- ---------- 6) 唯一约束 UNIQUE(user_id, principal_type)（Q5 严格 1:1）----------
-- 用唯一索引而非约束，便于 IF NOT EXISTS 幂等；与实体 @UniqueConstraint 语义一致。
CREATE UNIQUE INDEX IF NOT EXISTS uq_principal_bindings_user_type
    ON principal_bindings (user_id, principal_type);

-- ---------- 7) 外键：user_id -> users(id)（登录账号域，不再是记账账户域）----------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'claw.principal_bindings'::regclass
          AND conname  = 'principal_bindings_user_id_fkey'
    ) THEN
        ALTER TABLE claw.principal_bindings
            ADD CONSTRAINT principal_bindings_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES claw.users (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_pb_principal ON principal_bindings (principal_type, principal_id);
