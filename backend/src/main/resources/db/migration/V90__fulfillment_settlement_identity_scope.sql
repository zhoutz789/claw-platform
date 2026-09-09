-- =====================================================================
-- Claw 平台 V90 增量（履约结算恒等式校验范围收窄到仅 SETTLED）
--
-- 背景：V87 的 ck_fs_identity 约束为
--   CHECK (status NOT IN ('SETTLED','DONE') OR commission+balance = total)
-- 即 SETTLED 与 DONE 都要求满足恒等式。但 DONE 表示「人工关闭、未实际结算」
-- （如 AdminFulfillmentController 的 resolve 行政退款），其结算单行在挂起时
-- 仅捕获了部分字段（commission_amount 有值、balance_to_mfg 为 0），本就未动钱，
-- 16+0≠200 必然撞 CHECK。
--
-- 修正：恒等式只在真正结算成功（SETTLED）时必须成立；DONE（未结算关闭）不要求。
-- 语义澄清：SETTLED=已结算（资金守恒必须）；DONE=人工关闭（未结算，资金未动）。
--
-- ⚠️ 幂等：DROP CONSTRAINT IF EXISTS + ADD CONSTRAINT，二次执行无副作用。
-- ⚠️ 顶部 SET search_path = claw，表名严禁加 "claw." 前缀。
-- =====================================================================

SET search_path = claw;

ALTER TABLE fulfillment_settlements DROP CONSTRAINT IF EXISTS ck_fs_identity;

ALTER TABLE fulfillment_settlements
    ADD CONSTRAINT ck_fs_identity
    CHECK (status <> 'SETTLED' OR (commission_amount + balance_to_mfg = total_amount));
