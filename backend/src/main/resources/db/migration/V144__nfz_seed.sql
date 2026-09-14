-- ============================================================================
-- V144 无人机切片 3 —— 禁飞图层种子（schema=claw，幂等）
--
-- 依据：《无人机资产接入平台-增量技术设计方案-v3.md》§3.9（零容忍区 + 前期运营限制）
-- 分两类，语义不同：
--   A) **航空安全固有约束**（source='BAKED-IN'）：吴哥/APSARA 遗产区、金边王宫周边、
--      Techo 机场周边。**代码里恒定拒绝（PermitGate 不看 enabled、不受 permit_gate_mode
--      影响）**，enabled=true 仅为运营展示；此类行不应被任何接口关闭（服务层显式拒绝）。
--   B) **前期运营限制图层**（source='REGULATION-2025'）：泰柬边境省限飞层，含时段窗口。
--      enabled=true 生效；**政策放开后由运营侧 enabled=false 收起（配置层放开，不改码）**。
--
-- polygon_json 统一为 [[lng,lat], ...] 闭合环（**不重复首点**，环由实现隐式闭合）；
-- time_window_json 为 {"tz":"UTC","daily":[{"start":"HH:mm","end":"HH:mm"}]}，**时间一律 UTC**
-- （本种子 15:00–22:00 UTC = 柬埔寨当地 22:00–05:00 夜间禁飞）。
--
-- 幂等：按 (name, source) 先查后插，二次执行无副作用。
-- 不动 V1–V143；本迁移接在 V143 之后。
-- ============================================================================

SET search_path = claw;

-- ---------- A) 航空安全固有约束（BAKED-IN，代码恒定拒绝） ----------

-- A1) 吴哥 / APSARA 遗产区（暹粒，中心 103.8670,13.4125，半径约 5km 正八边形）
INSERT INTO nfz_layers (name, province, level, time_window_json, polygon_json, source, enabled,
                        created_at, updated_at)
SELECT 'Angkor / APSARA Heritage No-Fly Zone', 'SIEM_REAP', 'ABSOLUTE', NULL,
       '[[103.9132,13.4125],[103.8997,13.4442],[103.8670,13.4574],[103.8343,13.4442],[103.8208,13.4125],[103.8343,13.3808],[103.8670,13.3676],[103.8997,13.3808]]'::jsonb,
       'BAKED-IN', TRUE, now(), now()
 WHERE NOT EXISTS (SELECT 1 FROM nfz_layers
                    WHERE name = 'Angkor / APSARA Heritage No-Fly Zone' AND source = 'BAKED-IN');

-- A2) 金边王宫周边（中心 104.9282,11.5636，半径约 2km 正八边形）
INSERT INTO nfz_layers (name, province, level, time_window_json, polygon_json, source, enabled,
                        created_at, updated_at)
SELECT 'Phnom Penh Royal Palace No-Fly Zone', 'PHNOM_PENH', 'ABSOLUTE', NULL,
       '[[104.9465,11.5636],[104.9411,11.5763],[104.9282,11.5816],[104.9153,11.5763],[104.9099,11.5636],[104.9153,11.5509],[104.9282,11.5456],[104.9411,11.5509]]'::jsonb,
       'BAKED-IN', TRUE, now(), now()
 WHERE NOT EXISTS (SELECT 1 FROM nfz_layers
                    WHERE name = 'Phnom Penh Royal Palace No-Fly Zone' AND source = 'BAKED-IN');

-- A3) Techo 机场周边（金边新机场，中心 104.8500,11.1400，半径约 8km 正八边形）
INSERT INTO nfz_layers (name, province, level, time_window_json, polygon_json, source, enabled,
                        created_at, updated_at)
SELECT 'Techo International Airport No-Fly Zone', 'KANDAL', 'ABSOLUTE', NULL,
       '[[104.9237,11.1400],[104.9021,11.1908],[104.8500,11.2119],[104.7979,11.1908],[104.7763,11.1400],[104.7979,11.0892],[104.8500,11.0681],[104.9021,11.0892]]'::jsonb,
       'BAKED-IN', TRUE, now(), now()
 WHERE NOT EXISTS (SELECT 1 FROM nfz_layers
                    WHERE name = 'Techo International Airport No-Fly Zone' AND source = 'BAKED-IN');

-- ---------- B) 前期运营限制图层（REGULATION-2025，可随政策放开收起） ----------

-- B1) 泰柬边境省限飞层（西部边境带 lng 102.3–103.4 / lat 12.5–14.4，
--     覆盖 Pailin / Battambang / Banteay Meanchey / 西 Siem Reap / Oddar Meanchey），
--     时段限飞：UTC 15:00–22:00（柬埔寨当地 22:00–05:00 夜间禁飞）。
INSERT INTO nfz_layers (name, province, level, time_window_json, polygon_json, source, enabled,
                        created_at, updated_at)
SELECT 'Thai-Cambodia Border Province Operation Restriction', 'BORDER-TH', 'TIME_WINDOW',
       '{"tz":"UTC","daily":[{"start":"15:00","end":"22:00"}]}'::jsonb,
       '[[102.3,12.5],[103.4,12.5],[103.4,14.4],[102.3,14.4]]'::jsonb,
       'REGULATION-2025', TRUE, now(), now()
 WHERE NOT EXISTS (SELECT 1 FROM nfz_layers
                    WHERE name = 'Thai-Cambodia Border Province Operation Restriction'
                      AND source = 'REGULATION-2025');
