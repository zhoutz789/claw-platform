-- ============================================================================
-- V132 资金路由与清分 · 残差基准显式化（schema=claw）
-- ADD-ONLY：仅做「数据校正 + 约束显式化」，绝不修改 V1–V131，绝不改任何表结构语义。
--
-- 背景：
--   V131 的种子把「残差归厂家」编码为「basis=RATE, rate=1.000000, priority=99」这一*约定写入法*。
--   该写法有两个问题：
--     ① 语义隐晦 —— 一条 rate=1.0 的规则看起来像「厂家拿 100%」，读代码的人无法一眼看出它是兜底项；
--     ② 无法校验 —— 任何一条 rate>=1 的规则都会被引擎当成兜底项，误配置不会报错。
--
--   本迁移把兜底项升级为**显式基准** RuleBasis.RESIDUAL（Java 枚举同批新增），
--   SplitEngine 改为「优先识别 basis=RESIDUAL；场景内无 RESIDUAL 时回退识别 RATE&&rate>=1（历史兼容）」。
--
-- 关于 CHECK 约束：
--   V129 的 settlement_rule.basis **没有** CHECK 约束（已核对 V129/V131 全文），
--   因此无需 DROP/重建，只需① 校正数据、② 补一个显式枚举约束把新值固定下来。
--
-- 幂等：UPDATE 的条件（basis='RATE' AND rate>=1）在首次执行后不再命中；
--       DO 块内先查 pg_constraint 再 ADD CONSTRAINT，重复执行安全。
-- 表名不加 claw. 前缀（与 V87/V126/V127/V128–V131 一致）。
-- ============================================================================

SET search_path = claw;

-- ① 数据校正：种子里的残差兜底项由「RATE 1.0 约定」改为显式 RESIDUAL
UPDATE settlement_rule
   SET basis = 'RESIDUAL',
       rate = NULL,
       updated_at = now()
 WHERE biz_scene = 'CONSIGNMENT_SCAN'
   AND payee_type = 'MANUFACTURER'
   AND basis = 'RATE'
   AND rate >= 1;

-- ② 基准枚举显式化（V129 未建 CHECK；已存在则跳过，保证幂等与向前兼容）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'ck_settlement_rule_basis'
           AND conrelid = 'claw.settlement_rule'::regclass
    ) THEN
        ALTER TABLE settlement_rule
            ADD CONSTRAINT ck_settlement_rule_basis
            CHECK (basis IN ('RATE', 'FIXED', 'TIER', 'RESIDUAL'));
    END IF;
END $$;

-- ③ 兜底项唯一性护栏：同一场景至多一条 ACTIVE 的 RESIDUAL 规则。
--    部分唯一索引（WHERE 条件过滤），不影响 RATE/FIXED/TIER 的多规则并存。
CREATE UNIQUE INDEX IF NOT EXISTS uq_sr_residual_scene
    ON settlement_rule (biz_scene)
    WHERE basis = 'RESIDUAL' AND deleted = FALSE AND status = 'ACTIVE';
