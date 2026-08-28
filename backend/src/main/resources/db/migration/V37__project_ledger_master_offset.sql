-- V37：项目管理域 PROJECT_LEDGER 记账对冲侧（平台 MASTER 清算/汇总户）允许零/负余额
-- 背景：ProjectService.recordProjectEntry(INCOME) 以平台 MASTER 账户为借方对冲侧
--       （项目户 + / 平台汇总户 -），平台汇总户是维度记账对冲侧而非真实资金账户，
--        按设计允许零/负。原 claw.accounts 列级 CHECK (balance >= 0) 会拒绝 MASTER 转负，
--       导致 V36 项目管理域 PROJECT_LEDGER 记账在全新库（MASTER=0）上开箱即抛 42251。
-- 修复（方案 B 的 DB 层配套）：放宽 CHECK——仅 MASTER 允许为负，其余真实资金账户
--       （用户/资产/三专户等）仍强制 balance >= 0，语义不变、风险可控。
-- 注意：ddl-auto=none，本迁移独立新增，不改动 V36 表结构。
SET search_path = claw;

-- 删除 V1 自动命名的列级 CHECK（accounts_balance_check），改为带条件的命名约束。
-- 使用 DO 块做存在性判断，避免约束名因环境差异导致迁移失败。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'accounts'
          AND c.conname = 'accounts_balance_check'
    ) THEN
        ALTER TABLE claw.accounts DROP CONSTRAINT accounts_balance_check;
    END IF;
END $$;

ALTER TABLE claw.accounts ADD CONSTRAINT chk_accounts_balance_nonneg
    CHECK (balance >= 0 OR account_type = 'MASTER');
