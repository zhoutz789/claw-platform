-- =====================================================================
-- Claw 平台 V88 增量（履约结算金额精度放宽 NUMERIC(12,2) → NUMERIC(18,4)）
--
-- 背景：V50 建表时 settlement 金额列是 NUMERIC(12,2)，而 accounts / account_entries
--       已是 NUMERIC(18,4)。结算时「释放冻结 + 服务站提成 + 厂家货款」走 ledger 双记账，
--       金额在 18,4 下计算，若落回 12,2 会四舍五入，导致 V87 的恒等式 CHECK
--       （commission_amount + balance_to_mfg = total_amount）在 DB 侧必失败。
--
-- 范围：把 logistics_fee / commission_amount / balance_to_mfg / total_amount
--       统一放宽到 NUMERIC(18,4)。total_amount 在 V87 已为 18,4，此处 ALTER 是幂等 no-op。
--
-- ⚠️ 陷阱：表名不带 schema 前缀（顶部 SET search_path = claw）。
--   用 USING 显式转换，避免隐式 cast 在含 NULL/历史值时报错。放宽只会变宽，不丢数据。
-- ⚠️ 幂等：重复执行 ALTER TYPE 到相同类型在 PG 上是 no-op，安全。
-- =====================================================================

SET search_path = claw;

ALTER TABLE fulfillment_settlements
    ALTER COLUMN logistics_fee     TYPE NUMERIC(18,4) USING logistics_fee::numeric(18,4),
    ALTER COLUMN commission_amount TYPE NUMERIC(18,4) USING commission_amount::numeric(18,4),
    ALTER COLUMN balance_to_mfg    TYPE NUMERIC(18,4) USING balance_to_mfg::numeric(18,4),
    ALTER COLUMN total_amount      TYPE NUMERIC(18,4) USING total_amount::numeric(18,4);
