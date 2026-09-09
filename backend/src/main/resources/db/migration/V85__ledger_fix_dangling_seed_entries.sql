-- V85：清理种子数据里的「单边分录」，恢复全局借贷平衡。
--
-- 背景：早期演示种子（account_entries 里 biz_type='SWAP_SETTLE' 的 SO-2026-0001..0003）
-- 只插入了借方（用户账户 -0.58），没有对应的贷方，导致全局
--   sum(D) = 54001.74  ≠  sum(C) = 54000.00
-- 差 1.74 = 3 × 0.58。复式记账的基本不变量被破坏，任何对账脚本都会报警。
--
-- 处理原则：
--   1. 这些 txn 只有一条分录，本身就是不完整数据（换电结算的对手方应是平台清算户），
--      补贷方等于替历史数据编造一笔过账；删掉更诚实，业务上不影响 —— 换电订单本身存在
--      于 swap_orders 表，UI 展示不依赖这几条分录。
--   2. 按「txn_id 只有一条分录」识别，不硬编码 id（不同库的自增值可能不同）。
--   3. 幂等：删完之后条件不再成立，重复执行为 0 行。
--
-- 注意：演示账户（如 7001/7002/7008）的 balance 是「期初余额」式的演示值，
-- 与 account_entries 推导值本来就不相等，这是另一回事，本次不动。

SET search_path = claw;

DELETE FROM account_entries
WHERE biz_type = 'SWAP_SETTLE'
  AND txn_id IN (
      SELECT txn_id
      FROM account_entries
      GROUP BY txn_id
      HAVING COUNT(*) = 1
  );
