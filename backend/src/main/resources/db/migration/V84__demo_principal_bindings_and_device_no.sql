-- =====================================================================
-- Claw 平台 V84 增量（演示数据播种：主体绑定 + 演示设备编号）
-- 依据：真机验收（QA）反馈两个「功能点不动」的缺陷 —— 代码是对的，但演示库里
--       缺少让功能跑通的数据，导致任何人都无法实操验收：
--
--   ① principal_bindings 全库 0 行（sub_accounts 也是 0 行）
--      → 任何服务站账号调 POST /api/v1/station/consignment/inbound 都只能拿到
--        40301「This account is not bound to any service station」。
--        该 403 来自 StationScopeService.currentStationId() 的**失败关闭**
--        （厂家/平台管理员/未绑定主体一律拒绝，而不是回退到"第一个允许的站"），
--        实现本身正确，但演示库零绑定 ⇒ 这条刚上线的路径根本试不到。
--   ② 可入库的演示设备（devices id 17 / 18）device_no 为 NULL
--      → V82 刚做好的「扫码入库」（入参 deviceNo，形如 DEV-000123）在演示库里
--        无编号可扫，同样没法实操。
--
-- 本迁移只做**演示数据播种**（加性、幂等），不改任何既有结构、不删不改既有数据。
-- 生产环境如不需要这两个演示主体，可直接跳过本迁移（或执行后手工 DELETE 这两行）。
--
-- ⚠️ 陷阱 1：表名不带 schema 前缀 —— 本脚本顶部已 SET search_path = claw，
--    与 V25（建表）/ V79 / V80 / V82 / V83（改表）保持一致；写成
--    claw.principal_bindings 反而在 search_path 已切换时形成重复限定。
-- ⚠️ 陷阱 2：principal_bindings 的账号列是 **user_id**（登录账号 claw.users），
--    不是 account_id —— V47 建表时误用了 account_id（指向复式记账账户表
--    claw.accounts），V53 已 DROP 该列并换成 user_id + UNIQUE(user_id, principal_type)。
--    本脚本按 V53 之后的真实结构写；误写 account_id 会直接报列不存在。
-- ⚠️ 陷阱 3：principal_type 有 CHECK 约束，取值仅限
--    'MANUFACTURER' / 'STATION' / 'MERCHANT'，字面量必须大写。
-- ⚠️ 陷阱 4：devices.device_no 有 UNIQUE 约束（devices_device_no_key）。
--    本脚本只改 device_no IS NULL 的行，且编号由设备主键派生，绝不会撞号。
-- ⚠️ 陷阱 5：演示主体的 id 在不同库上不保证相同（序列/播种顺序差异），
--    故**一律按手机号反查 user id**、按业务键反查主体 id，不硬编码 12/13/15。
--    干净库上若没有对应的 stations / manufacturers / inventory 行，
--    相应的 INSERT ... SELECT 会产出 0 行，静默跳过，不报错。
-- 幂等性：INSERT ... ON CONFLICT DO NOTHING（吸收 uq_principal_bindings_user_type 冲突）；
--    UPDATE 带 device_no IS NULL 条件；二次执行恒为 no-op。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 演示账号 ↔ 业务主体绑定（principal_bindings）----------
-- 真实列结构（V47 建表 + V53 订正后）：
--   id             BIGSERIAL PK
--   principal_type VARCHAR(20)  NOT NULL  CHECK IN ('MANUFACTURER','STATION','MERCHANT')
--   principal_id   BIGINT       NOT NULL  -- manufacturer_id 或 station_id
--   created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
--   user_id        BIGINT       NOT NULL  REFERENCES claw.users(id)
--   UNIQUE (user_id, principal_type)   -- 索引 uq_principal_bindings_user_type
-- 注：没有 account_id 列（V53 已 DROP），也没有 updated_at / tenant_id / deleted 列。

-- 1.1 演示-服务站（13800000005）→ STATION + 站点 id 1（俄罗斯市场站）
INSERT INTO principal_bindings (user_id, principal_type, principal_id, created_at)
SELECT u.id, 'STATION', s.id, now()
  FROM users u
  JOIN stations s ON s.id = 1
 WHERE u.phone = '13800000005'
ON CONFLICT DO NOTHING;

-- 1.2 演示-厂家（13800000004）→ MANUFACTURER + 持有演示库存的厂家主体
-- 主体取「持有 devices 17/18 台账的厂家」（即 inventory.owner_manufacturer_id），
-- 这样厂家演示账号登录后能看到这批待入库设备的库存，且服务站扫码入库时
-- 台账里的货权方与本主体一致；取不到时回退到 manufacturers 里 id 最小的一家，
-- 干净库上 manufacturers 为空表则 COALESCE 得 NULL → JOIN 出 0 行 → 静默跳过。
INSERT INTO principal_bindings (user_id, principal_type, principal_id, created_at)
SELECT u.id, 'MANUFACTURER', m.id, now()
  FROM users u
  JOIN manufacturers m
    ON m.id = COALESCE(
         (SELECT owner_manufacturer_id
            FROM inventory
           WHERE device_id IN (17, 18)
             AND owner_manufacturer_id IS NOT NULL
           ORDER BY id
           LIMIT 1),
         (SELECT min(id) FROM manufacturers))
 WHERE u.phone = '13800000004'
ON CONFLICT DO NOTHING;

-- ---------- 2) 演示设备补编号（devices.device_no）----------
-- 只给 inventory 台账里**当前可入库**的设备补编号：即"还没在站"
-- （inventory.holder_station_id IS NULL）的那些台，经 inventory.device_id 关联到 devices。
-- 演示库现状：inventory id 10 / 11（OWNED_BY_MFG、PRODUCING、holder_station_id 为空）
-- 对应 devices id 17 / 18 ⇒ 得到 DEV-000017 / DEV-000018。
-- device_no IS NULL 条件保证幂等；lpad 到 6 位对齐扫码枪期望的 DEV-000123 形态。
UPDATE devices d
   SET device_no = 'DEV-' || lpad(d.id::text, 6, '0')
 WHERE d.device_no IS NULL
   AND EXISTS (SELECT 1
                 FROM inventory i
                WHERE i.device_id = d.id
                  AND i.holder_station_id IS NULL);
