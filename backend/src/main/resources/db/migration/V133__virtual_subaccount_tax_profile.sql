-- ============================================================================
-- V133 资金路由与清分 · 虚拟子户税务档案（schema=claw）
-- ADD-ONLY：仅给 virtual_subaccount 加 2 个可空列 + 可选枚举约束，绝不修改 V1–V132。
--
-- 背景（设计附录 B.3 / B.5 / C.2，T11 WHT 代扣引擎）：
--   平台向第三方付款前须按收款方「纳税人状态 + WHT 类别」判断并代扣预扣税（WHT）。
--   税务档案挂在收款方虚拟子户上（taxpayer_status / wht_category），由 WhtEngine 读取。
--
-- 幂等：ADD COLUMN IF NOT EXISTS；枚举约束先查 pg_constraint 再 ADD，重复执行安全。
-- 表名不加 claw. 前缀（与 V87/V126–V132 一致）。
-- ============================================================================

SET search_path = claw;

ALTER TABLE virtual_subaccount
    ADD COLUMN IF NOT EXISTS taxpayer_status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS wht_category   VARCHAR(20);

COMMENT ON COLUMN virtual_subaccount.taxpayer_status
    IS '纳税人状态 REGISTERED/UNREGISTERED/INDIVIDUAL/NON_RESIDENT（WHT 代扣判定，T11 新增）';
COMMENT ON COLUMN virtual_subaccount.wht_category
    IS 'WHT 类别 SERVICE/RENTAL/DIVIDEND/NONE（T11 新增）';

-- 可选枚举约束（幂等：已存在则跳过，保证向前兼容）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'ck_vsa_taxpayer_status'
           AND conrelid = 'claw.virtual_subaccount'::regclass
    ) THEN
        ALTER TABLE virtual_subaccount
            ADD CONSTRAINT ck_vsa_taxpayer_status
            CHECK (taxpayer_status IN ('REGISTERED','UNREGISTERED','INDIVIDUAL','NON_RESIDENT'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'ck_vsa_wht_category'
           AND conrelid = 'claw.virtual_subaccount'::regclass
    ) THEN
        ALTER TABLE virtual_subaccount
            ADD CONSTRAINT ck_vsa_wht_category
            CHECK (wht_category IN ('SERVICE','RENTAL','DIVIDEND','NONE'));
    END IF;
END $$;
