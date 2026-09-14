-- ============================================================================
-- V135 资金路由与清分 · R5 共享池分成规则种子（schema=claw）
-- ADD-ONLY：仅播种 RENTAL_SPLIT 场景的分账规则，绝不修改 V1–V134。
--
-- 背景（设计 §5.2 / 附录 C.2，T08 R5 共享池分成接入）：
--   租赁完成（completeRental）复用统一分账引擎 SplitEngine + T05 记账，四方逐腿入账，
--   每腿经 T11 WHT 代扣。规则源：settlement_rule 优先（本迁移播种）；缺失时回退既有
--   revenue_split_rules（所有人 70% / 站 15% / 平台 10% / 保险 5%）。
--   所有人（OWNER）按 RESIDUAL 显式兜底（V132 口径），残差 = total − 15% − 10% − 5% = 70%。
--
-- 幂等：依赖 settlement_rule 的 UNIQUE (biz_scene, payee_type, rule_version)（V129），ON CONFLICT 跳过。
-- 表名不加 claw. 前缀（与 V87/V126–V134 一致）。
-- ============================================================================

SET search_path = claw;

INSERT INTO settlement_rule (biz_scene, payee_type, basis, rate, priority, currency, settle_cycle, status)
VALUES
  ('RENTAL_SPLIT', 'PLATFORM', 'RATE',    0.100000,  1, 'USD', 'T+0', 'ACTIVE'),
  ('RENTAL_SPLIT', 'STATION',  'RATE',    0.150000, 10, 'USD', 'T+7', 'ACTIVE'),
  ('RENTAL_SPLIT', 'INSURER',  'RATE',    0.050000, 20, 'USD', 'T+7', 'ACTIVE'),
  ('RENTAL_SPLIT', 'OWNER',    'RESIDUAL', NULL,     99, 'USD', 'T+7', 'ACTIVE')
ON CONFLICT (biz_scene, payee_type, rule_version) DO NOTHING;
