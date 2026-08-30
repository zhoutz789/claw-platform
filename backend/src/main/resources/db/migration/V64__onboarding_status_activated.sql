-- =====================================================================
-- Claw 平台 V64 增量（入驻状态命名统一：ACTIVE → ACTIVATED）
--
-- 背景（真库冒烟暴露的阻断缺陷）：
--   · 组织入驻状态列 stations/manufacturers/merchants.onboarding_status 的
--     「激活态」在库里被写成了 'ACTIVE'；
--   · 但入驻申请单状态机（OnboardingApplicationStatus）的激活终态叫 'ACTIVATED'。
--   二者并存导致同一套语义两个字面量：
--     · V60 回填的历史服务站 → onboarding_status = 'ACTIVE'
--     · 新入驻激活的组织     → onboarding_status = 'ACTIVATED'
--   写入守卫 OrgWritableGuard 只能认其中一个，另一批主体就在「新建类写入点」
--   被 40340 org.disabled.readonly 拦下，冒烟表现为历史服务站无法入站。
--
-- 修复口径（与 OnboardingStatus 枚举保持一致）：
--   · 入驻治理的激活态<b>统一为 'ACTIVATED'</b>，与申请单终态同名；
--   · 'ACTIVE' 从此只属于三张主体表的<b>运营状态列 status</b>（营业中）。
--     两者此前同名，SQL 里极易写错，这正是本次缺陷的根因。
--
-- 为什么新增 V64 而不是改 V60：
--   · V60 已在真库上应用过，Flyway 默认开启校验（application.yml 未关闭
--     validate-on-migrate），就地改写已应用脚本会直接 checksum 校验失败、阻断启动。
--   · V64 对「新装」与「存量」两条路径都成立：
--       新装：V60 回填 'ACTIVE' → V64 转成 'ACTIVATED'；
--       存量：V60 早已写入 'ACTIVE' → V64 同样转成 'ACTIVATED'。
--
-- 幂等性：
--   · UPDATE 以 onboarding_status = 'ACTIVE' 为条件，二次执行影响 0 行；
--   · 约束用 DO 块按名字判断后再建。
--
-- 不触碰：
--   · stations.status / manufacturers.status / merchants.status（运营状态列，
--     取值 ACTIVE/CLOSED/PENDING/REJECTED，与本列无关，一个字都不改）；
--   · claw.custody_records（V48 旧占有权表）与 claw.account_entries.direction。
-- =====================================================================

SET search_path = claw;

-- ---------------------------------------------------------------------
-- 1. 存量回填：三张主体表的入驻治理激活态统一为 'ACTIVATED'
--    只动 onboarding_status 列，且只动值为 'ACTIVE' 的行 ——
--    PENDING / DISABLED / REJECTED / NULL 一律保持原样，不把已被平台禁用的
--    主体误置为激活态。
-- ---------------------------------------------------------------------
UPDATE stations
   SET onboarding_status = 'ACTIVATED'
 WHERE onboarding_status = 'ACTIVE';

UPDATE manufacturers
   SET onboarding_status = 'ACTIVATED'
 WHERE onboarding_status = 'ACTIVE';

UPDATE merchants
   SET onboarding_status = 'ACTIVATED'
 WHERE onboarding_status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- 2. 状态变更留痕表：同一套词表，历史流水一并归一，避免日志与枚举脱节。
--    只改 from_status / to_status 两个状态列；action 列
--    （ACTIVATE / DISABLE / ENABLE / REJECT）不属于本词表，不动。
-- ---------------------------------------------------------------------
UPDATE onboarding_org_status_logs
   SET from_status = 'ACTIVATED'
 WHERE from_status = 'ACTIVE';

UPDATE onboarding_org_status_logs
   SET to_status = 'ACTIVATED'
 WHERE to_status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- 3. 防御：给三张主体表加 CHECK 约束，杜绝再次出现 'ACTIVE' / 'ACTIVATED' 混用。
--    用 NOT VALID —— 只约束后续写入，不回扫存量行，
--    因此即使存量里存在历史脏值也不会让迁移失败、阻断启动。
--    onboarding_status 可为 NULL，NULL 在 CHECK 下判定为 UNKNOWN，不违反约束。
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stations_onboarding_status') THEN
        ALTER TABLE claw.stations
            ADD CONSTRAINT ck_stations_onboarding_status
            CHECK (onboarding_status IS NULL
                   OR onboarding_status IN ('PENDING', 'ACTIVATED', 'DISABLED', 'REJECTED')) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_manufacturers_onboarding_status') THEN
        ALTER TABLE claw.manufacturers
            ADD CONSTRAINT ck_manufacturers_onboarding_status
            CHECK (onboarding_status IS NULL
                   OR onboarding_status IN ('PENDING', 'ACTIVATED', 'DISABLED', 'REJECTED')) NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_merchants_onboarding_status') THEN
        ALTER TABLE claw.merchants
            ADD CONSTRAINT ck_merchants_onboarding_status
            CHECK (onboarding_status IS NULL
                   OR onboarding_status IN ('PENDING', 'ACTIVATED', 'DISABLED', 'REJECTED')) NOT VALID;
    END IF;
END $$;
