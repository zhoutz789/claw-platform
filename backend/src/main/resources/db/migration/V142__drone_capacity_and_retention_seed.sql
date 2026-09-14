-- ============================================================================
-- V142 无人机切片 2 —— 容量种子（PARALLEL）+ 留存策略（DRONE）+ 术语治理
--
-- 依据：《无人机资产接入平台-增量技术设计方案-v3.md》§3.7 / §4（R7 容量预订、R1 航迹留存）
--
-- 1) capacity_plans 无人机容量产品种子：capacity_type='PARALLEL'
--    —— 无人机可真实并行使用产能（SERIAL 是「优先权+回佣资格」，不适用于空中作业额度）。
--    说明：当前库内尚无无人机 asset/product（机型种子 V139 为目录配置），故本种子
--    asset_id / product_id / pool_entry_id 均留空（V81 已放开 asset_id NOT NULL），
--    owner_user_id 挂平台方（users.id=1），纯「目录级容量产品」供前端展示与下单入口。
--
-- 2) retention_policy(asset_class='DRONE')：无人机航迹/影像留存档位。
--    与摄像头子系统共用同一张留存策略表（V91），DRONE 档位独立可调。
--
-- 3) 术语治理：菜单名去「低空经济/低空运营」旧中文串 —— 若某历史迁移把中文串直接落进
--    permissions.name，此处改写为 i18n key 引用 nav:group.drone；**带 i18n 引用守卫**
--    （仅当 name 恰为旧中文串时才改写），避免覆盖已是 key 的现值。
--
-- ⚠️ 幂等：INSERT 带 WHERE NOT EXISTS / ON CONFLICT DO NOTHING；UPDATE 带守卫与 code 限定。
-- ⚠️ 不动 V1–V141；本迁移接在 V141 之后。
-- ============================================================================

SET search_path = claw;

-- ---------- 1) 无人机容量产品种子（PARALLEL） ----------
INSERT INTO capacity_plans (asset_id, pool_entry_id, owner_user_id, total_units, subscribed_units,
                            unit_price, capacity_type, rebate_rate, window_start, window_end,
                            status, product_id, plan_desc, tenant_id, deleted, created_at, updated_at)
SELECT NULL, NULL, 1, 30, 0,
       1500.0000, 'PARALLEL', 0.1000, NULL, NULL,
       'OPEN', NULL,
       '[DRONE_CAP_SEED] 低空无人机作业容量（可并行使用额度）：30 个容量单位，'
       || '每单位预付款 1500.0000；适用于植保/巡检/物流等无人机作业产能预订。'
       || '风险提示：回佣按真实运营绩效计提，不保底不保息；容量额度不可转让给第三方资产。',
       1, FALSE, now(), now()
 WHERE NOT EXISTS (
       SELECT 1 FROM capacity_plans
        WHERE capacity_type = 'PARALLEL'
          AND plan_desc LIKE '[DRONE_CAP_SEED]%'
 );

-- ---------- 2) 无人机留存策略 ----------
INSERT INTO retention_policy (asset_class, raw_window_days, sparse_interval_sec, event_permanent)
VALUES ('DRONE', 30, 300, TRUE)
ON CONFLICT (asset_class) DO NOTHING;

-- ---------- 3) 术语治理：菜单去「低空经济/低空运营」旧中文串 ----------
-- 守卫：仅当 name 仍是旧中文串（历史迁移直落的中文）才改；现值已是 i18n key（nav:group.drone）则不动。
UPDATE permissions
   SET name = 'nav:group.drone'
 WHERE code = 'menu:drone'
   AND name IN ('低空经济', '低空运营');

UPDATE permissions
   SET name = 'nav:item.drone-ops'
 WHERE code = 'menu:drone-ops'
   AND name IN ('低空经济', '低空运营', '作业与安全管控');
