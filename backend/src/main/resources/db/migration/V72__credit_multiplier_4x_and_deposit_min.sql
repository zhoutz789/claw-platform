-- =====================================================================
-- Claw 平台 V72 增量（寄售授信规则修正 · 周老板 2026-09-06 拍板）
-- 依据：用户补充说明①「信用额度是缴纳保证金的四倍」「最低保证金暂定 5000」
-- 范围：
--   · onboarding_deposit_tiers    授信倍率 3 → 4，权益文案同步
--   · system_config              ONBOARDING_CREDIT_MULTIPLIER_DEFAULT 3 → 4
--   · stations / manufacturers / merchants  冗余 credit_limit 按 4× 重算
--   · system_config              新增 ONBOARDING_DEPOSIT_MIN=5000（最低保证金，暂定）
-- 全量幂等：WHERE credit_multiplier = 3.0000 仅命中旧值；benefit_desc 文本替换幂等。
-- 注意：不修改 V60（已应用迁移改内容会触发 Flyway 校验失败），本迁移在 V60 之后运行，
--       新装库先落 3× 再被本迁移修正为 4×，效果一致。
-- =====================================================================

SET search_path = claw;

-- ---------- (1) 档位授信倍率 3 → 4 ----------
UPDATE onboarding_deposit_tiers
SET credit_multiplier = 4.0000
WHERE credit_multiplier = 3.0000;

-- ---------- (2) 权益文案同步（15,000/60,000/150,000 → 20,000/80,000/200,000）----------
UPDATE onboarding_deposit_tiers
SET benefit_desc = replace(replace(replace(benefit_desc,
        '15,000', '20,000'), '60,000', '80,000'), '150,000', '200,000');

-- ---------- (3) system_config 默认倍率 3 → 4 ----------
UPDATE system_config
SET config_value = '4'
WHERE config_key = 'ONBOARDING_CREDIT_MULTIPLIER_DEFAULT';

-- ---------- (4) 已激活主体的冗余 credit_limit 按 4× 重算 ----------
-- 规则改为 4× 后，历史站点/厂家/商家的冗余额度应同步为 deposit × 4（与档位一致）。
-- 仅重算已设额度者，历史未入驻（credit_limit IS NULL）放行不处理。
UPDATE stations s
SET credit_limit = (SELECT (t.deposit_amount * 4) FROM onboarding_deposit_tiers t WHERE t.id = s.deposit_tier_id)
WHERE s.deposit_tier_id IS NOT NULL AND s.credit_limit IS NOT NULL;

UPDATE manufacturers m
SET credit_limit = (SELECT (t.deposit_amount * 4) FROM onboarding_deposit_tiers t WHERE t.id = m.deposit_tier_id)
WHERE m.deposit_tier_id IS NOT NULL AND m.credit_limit IS NOT NULL;

UPDATE merchants mc
SET credit_limit = (SELECT (t.deposit_amount * 4) FROM onboarding_deposit_tiers t WHERE t.id = mc.deposit_tier_id)
WHERE mc.deposit_tier_id IS NOT NULL AND mc.credit_limit IS NOT NULL;

-- ---------- (5) 最低保证金（暂定 5000）----------
INSERT INTO system_config (config_key, config_value, category, description, data_type, editable)
VALUES ('ONBOARDING_DEPOSIT_MIN', '5000', 'ONBOARDING',
        '最低保证金（周老板 2026-09-06 暂定 5000），低于此值不可选档；运营可在 settings 调整', 'NUMBER', TRUE)
ON CONFLICT (config_key) DO NOTHING;
