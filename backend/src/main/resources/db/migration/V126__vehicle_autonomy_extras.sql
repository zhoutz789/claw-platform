-- ============================================================================
-- V126 无人车 autonomy 子域补强（schema=claw）
-- 仅新增列 / 种子，绝不修改 V1–V125。
--
-- 1) vehicle_product_classes 增加 autonomy_level（NONE / ASSISTED / FULL），
--    供"产品类 autonomy_level=FULL → 建档时调用 provisionForNewAutonomousAsset"使用。
--
-- 2) 自动驾驶相关枚举值已就位，无需新增：
--    · AssetCapability.AUTONOMY
--    · TaskType.AUTO_DELIVERY / AUTO_SWEEP / AUTO_PATROL
--    均已在 V122 之前的枚举中定义。
--
-- 3) revenue_split_rules 的 AUTONOMY 分账：实际表无 type/AUTONOMY 判别列，
--    且 asset_id 有 FK → claw.assets(id)、并带硬 CHECK 约束
--    （platform_rate=0.10 / insurance_rate=0.05 / 四项合计=1.0 / owner>=0.50 / station>=0.15）。
--    因此无法在不破坏共享池分成语义、且无需真实资产 FK 的前提下插入一行"平台级 AUTONOMY"种子。
--    computeAutonomyRevenueSplit 改为读取该资产既有 revenue_split_rules 行
--    （即"算法归平台"可配分成），缺失时回退默认 0.60/0.30/0.10（详见 AutonomyRevenueService）。
-- ============================================================================

SET search_path = claw;

ALTER TABLE vehicle_product_classes
    ADD COLUMN IF NOT EXISTS autonomy_level VARCHAR(32) NOT NULL DEFAULT 'NONE';
