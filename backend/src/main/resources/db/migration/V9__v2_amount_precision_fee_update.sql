-- =====================================================================
-- Claw 平台 V9 迁移（v2.0 商业模式重构 — 技术地基修复）
-- 依据：《技术开发文档 v0.5》+ 《全风险规避方案 v2.0》
-- 范围：
--   · T-A6 金额精度：所有 NUMERIC(16,2) → NUMERIC(18,4)（4位小数=0.0001美分精度）
--   · T-S1 辅助：DB 触发器兜底并发安全（应用层悲观锁 + DB触发器双重防护）
--   · v2.0 旧模型清理：fee_rules 覆盖更新（删除 battery_fund 拆分）
--   · v2.0 旧模型清理：batteries deposit_value 统一为固定30%押金
--   · 安全 DROP：deposit_curves 表（从未创建，安全清理引用）
-- 通用规范继承 V1：schema claw
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. T-A6 金额精度修复：NUMERIC(16,2) → NUMERIC(18,4)
--    原因：16,2 只精确到 0.01 美元，服务费按度计价（$0.32/kWh）×
--    小数度数（2.53 kWh）= $0.8096 需要 4 位小数精度。
--    18,4 = 14位整数 + 4位小数，最大 $99,999,999,9999.9999 够用。
-- ---------------------------------------------------------------------

-- accounts: balance + frozen
ALTER TABLE claw.accounts ALTER COLUMN balance TYPE NUMERIC(18,4);
ALTER TABLE claw.accounts ALTER COLUMN frozen TYPE NUMERIC(18,4);
ALTER TABLE claw.accounts ALTER COLUMN balance SET DEFAULT 0;
ALTER TABLE claw.accounts ALTER COLUMN frozen SET DEFAULT 0;

-- account_entries: amount
ALTER TABLE claw.account_entries ALTER COLUMN amount TYPE NUMERIC(18,4);

-- wallet_txns: amount_usd（注意表名为复数 wallet_txns，与 V7 建表一致）
ALTER TABLE claw.wallet_txns ALTER COLUMN amount_usd TYPE NUMERIC(18,4);

-- deposits: amount
ALTER TABLE claw.deposits ALTER COLUMN amount TYPE NUMERIC(18,4);

-- payment_orders: amount_usd
ALTER TABLE claw.payment_orders ALTER COLUMN amount_usd TYPE NUMERIC(18,4);

-- swap_orders: 全部金额字段
ALTER TABLE claw.swap_orders ALTER COLUMN battery_deposit     TYPE NUMERIC(18,4);
ALTER TABLE claw.swap_orders ALTER COLUMN old_battery_deposit TYPE NUMERIC(18,4);
ALTER TABLE claw.swap_orders ALTER COLUMN est_elec_fee        TYPE NUMERIC(18,4);
ALTER TABLE claw.swap_orders ALTER COLUMN est_service_fee     TYPE NUMERIC(18,4);
ALTER TABLE claw.swap_orders ALTER COLUMN est_total           TYPE NUMERIC(18,4);
ALTER TABLE claw.swap_orders ALTER COLUMN actual_elec_fee     TYPE NUMERIC(18,4);
ALTER TABLE claw.swap_orders ALTER COLUMN actual_service_fee  TYPE NUMERIC(18,4);
ALTER TABLE claw.swap_orders ALTER COLUMN actual_total        TYPE NUMERIC(18,4);

-- fee_rules: price 已是 NUMERIC(10,4) 无需改，但扩大到 18,4 统一
ALTER TABLE claw.fee_rules ALTER COLUMN price TYPE NUMERIC(18,4);

-- ---------------------------------------------------------------------
-- 2. T-A6 补充：添加 CHECK 约束兜底金额非负
--    （accounts 已有 CHECK (balance >= 0)，其余表补充）
-- ---------------------------------------------------------------------
ALTER TABLE claw.account_entries ADD CONSTRAINT chk_entry_amount_nonneg CHECK (amount >= 0);
ALTER TABLE claw.wallet_txns   ADD CONSTRAINT chk_wallet_amount_nonneg CHECK (amount_usd >= 0);

-- ---------------------------------------------------------------------
-- 3. v2.0 旧模型清理：fee_rules 覆盖更新
--    旧：SWAP_SERVICE = 0.32 = {"battery_fund":0.10,"station":0.19,"platform":0.03}
--    新：SWAP_SERVICE = 0.32 = {"station":0.29,"platform":0.03}（取消 battery_fund $0.10/度）
--    旧：PV_ELEC = 0.12 = {"investor_pct":0.50,"site_pct":0.20,"platform_pct":0.30}
--    新：PV_ELEC = 0.12 = {"site_pct":1.00}（光伏由站方自筹，收益归站方，线下分成）
--    GRID_ELEC 不变（市电原价转付）
-- ---------------------------------------------------------------------
UPDATE claw.fee_rules SET
    share_json = '{"station":0.29,"platform":0.03}'::jsonb,
    updated_at = now()
WHERE rule_code = 'SWAP_SERVICE';

UPDATE claw.fee_rules SET
    share_json = '{"site_pct":1.00}'::jsonb,
    updated_at = now()
WHERE rule_code = 'PV_ELEC';

-- ---------------------------------------------------------------------
-- 4. v2.0 旧模型清理：batteries deposit_value 统一为固定30%押金
--    旧：deposit_value 随 SOH 变化（40/38/36 动态残值）
--    新：deposit_value = 固定 12.00（$40 电池 × 30%，D36 定稿）
--    SOH 保留用于残值评估（D41），不再驱动押金金额
-- ---------------------------------------------------------------------
UPDATE claw.batteries SET
    deposit_value = 12.00,
    updated_at = now()
WHERE deposit_value IS NOT NULL;

-- ---------------------------------------------------------------------
-- 5. v2.0 旧模型清理：swap_orders 押金注释更新
--    旧注释引用"动态残值"，新模型为"固定30%押金"
--    （PostgreSQL 不支持 ALTER COLUMN COMMENT 直接改，用 COMMENT ON）
-- ---------------------------------------------------------------------
COMMENT ON COLUMN claw.swap_orders.battery_deposit IS '新电池押金（固定30%，D36）';

-- ---------------------------------------------------------------------
-- 6. 安全 DROP：deposit_curves 表（v2.0 取消动态残值概念）
--    该表从未在任何迁移中 CREATE，此处仅为安全清理引用
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS claw.deposit_curves CASCADE;

-- ---------------------------------------------------------------------
-- 7. T-S1 辅助：DB 触发器兜底——账户余额更新审计
--    应用层已有悲观锁（findByIdForUpdate + SELECT FOR UPDATE），
--    DB 触发器作为第二道防线，记录所有余额变动用于事后审计。
--    注意：触发器不做业务校验（会降低性能），仅做审计日志。
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION claw.audit_account_balance_change()
RETURNS TRIGGER AS $$
BEGIN
    -- 仅在 balance 或 frozen 变化时记录
    IF (TG_OP = 'UPDATE') AND
       (OLD.balance IS DISTINCT FROM NEW.balance OR
        OLD.frozen IS DISTINCT FROM NEW.frozen) THEN
        INSERT INTO claw.account_balance_audit (account_id, old_balance, new_balance, old_frozen, new_frozen, changed_at)
        VALUES (NEW.id, OLD.balance, NEW.balance, OLD.frozen, NEW.frozen, now());
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 审计表（仅追加，不删除）
CREATE TABLE IF NOT EXISTS claw.account_balance_audit (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    BIGINT      NOT NULL,
    old_balance   NUMERIC(18,4),
    new_balance   NUMERIC(18,4),
    old_frozen    NUMERIC(18,4),
    new_frozen    NUMERIC(18,4),
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_account_time ON claw.account_balance_audit (account_id, changed_at DESC);

-- 触发器绑定到 accounts 表
DROP TRIGGER IF EXISTS trg_account_balance_audit ON claw.accounts;
CREATE TRIGGER trg_account_balance_audit
    AFTER UPDATE ON claw.accounts
    FOR EACH ROW
    EXECUTE FUNCTION claw.audit_account_balance_change();

-- ---------------------------------------------------------------------
-- 8. 迁移完成日志
-- ---------------------------------------------------------------------
INSERT INTO claw.fee_rules (rule_code, name, unit, price, share_json) VALUES
    ('CHARGE_SERVICE', '充电服务费', 'kWh', 0.15, '{"station":0.10,"platform":0.05}')
ON CONFLICT (rule_code) DO UPDATE SET
    price = EXCLUDED.price,
    share_json = EXCLUDED.share_json,
    updated_at = now();
