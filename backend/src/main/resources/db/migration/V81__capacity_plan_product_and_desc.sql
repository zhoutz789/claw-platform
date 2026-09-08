-- =====================================================================
-- Claw 平台 V81 增量（容量预定：挂商品 + 计划说明 + 权限种子）
-- 依据：周老板 2026-09 反馈「容量预定应该是商品寄售前的一个按钮，点开后显示该
--       商品的容量预定订单 + 计划说明（风险提示/操作方法），只填份数并付款；
--       点击按钮时数据要联动，不能让前端手填一堆 ID」。
--
-- 范围：
--   1) capacity_plans 增加 product_id BIGINT
--        —— 容量计划挂到「商品」上（厂家发布的 products），前端「容量预定」按钮
--           直接带 productId 进出，不再让使用者填 assetId/poolEntryId 等裸 ID。
--           不加外键约束：products 与 capacity_plans 生命周期不同，且历史计划
--           （V71 起按 asset 建的老数据）product_id 为空，避免加 FK 后老数据/删商品受阻。
--   2) capacity_plans 增加 plan_desc TEXT
--        —— 计划说明（风险提示 + 操作方法）。厂家建计划时填一次，客户侧只读展示，
--           保证「同一计划对所有人说法一致」，杜绝各端各写一份说明。
--   3) capacity_plans.asset_id 去掉 NOT NULL
--        —— 新流程按商品建计划，不再强制绑定资产；老流程（按 asset）数据不受影响。
--   4) idx_capacity_plans_product：按商品查计划的索引（GET /plans?productId= 走这里）。
--   5) capacity_subscriptions 增加 paid_at TIMESTAMPTZ
--        —— 付款完成时间。付款即落库（subscribe 成功 = 已付款），供订单列表展示。
--   6) 权限种子 3 条（BUTTON）：mfg:capacity:create / mfg:capacity:view /
--      capacity:subscribe，并挂到角色模板 + 回写 roles.grants。
--
-- ⚠️ 陷阱 1：表名不带 schema 前缀 —— 本脚本顶部已 SET search_path = claw，
--    与 V25 / V79 / V80 保持一致；再写 claw.xxx 会形成重复限定。
-- ⚠️ 陷阱 2：ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS —— 幂等，
--    二次执行无副作用；新增列均可空，不回填、不破坏老数据。
-- ⚠️ 陷阱 3：roles.grants 回写只覆盖 MANUFACTURER / STATION（V77 陷阱 1/2）：
--    PLATFORM_ADMIN 走 V40 种下的 '["*"]' 通配符，不能回写否则从「通配」降级为
--    「白名单」；CUSTOMER 的 grants 是 V11 种下的对象结构，parseGrants 只认数组，
--    回写会清空老用户权限。
-- ⚠️ 陷阱 4：permissions 表有 path / icon 等可空列，按钮位沿用 V77 的 5 列写法。
-- 幂等性：DDL 全 IF NOT EXISTS；INSERT 全 ON CONFLICT DO NOTHING；UPDATE 按 code 限定。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 容量计划挂商品 ----------
ALTER TABLE capacity_plans
  ADD COLUMN IF NOT EXISTS product_id BIGINT;

-- ---------- 2) 计划说明（风险提示 + 操作方法，客户侧只读）----------
ALTER TABLE capacity_plans
  ADD COLUMN IF NOT EXISTS plan_desc TEXT;

-- ---------- 3) asset_id 改为可空（新流程按商品建计划，不再强制绑资产）----------
ALTER TABLE capacity_plans
  ALTER COLUMN asset_id DROP NOT NULL;

-- ---------- 4) 按商品查计划索引 ----------
CREATE INDEX IF NOT EXISTS idx_capacity_plans_product
  ON capacity_plans (product_id);

-- ---------- 5) 定购付款时间 ----------
ALTER TABLE capacity_subscriptions
  ADD COLUMN IF NOT EXISTS paid_at TIMESTAMPTZ;

-- ---------- 6) 权限种子（BUTTON 位）----------
-- parent_code 挂 menu:capacity-booking（V77 已种且已挂 MANUFACTURER/STATION/PLATFORM_ADMIN）。
INSERT INTO permissions (code, name, ptype, parent_code, sort_no) VALUES
  ('mfg:capacity:create', '创建容量计划', 'BUTTON', 'menu:capacity-booking', 60),
  ('mfg:capacity:view',   '查看容量计划', 'BUTTON', 'menu:capacity-booking', 61),
  ('capacity:subscribe',  '预定容量',     'BUTTON', 'menu:capacity-booking', 62)
ON CONFLICT (code) DO NOTHING;

-- ---------- 7) 角色模板挂载 ----------
-- 厂家：建/看自己的容量计划；服务站：查看；平台管理员：全部；客户：预定容量。
-- 不挂 capacity:subscribe 给 MANUFACTURER/STATION —— 预定是客户端动作。
INSERT INTO role_template_permissions (template_code, permission_code) VALUES
  ('MANUFACTURER',   'mfg:capacity:create'),
  ('MANUFACTURER',   'mfg:capacity:view'),
  ('STATION',        'mfg:capacity:view'),
  ('PLATFORM_ADMIN', 'mfg:capacity:create'),
  ('PLATFORM_ADMIN', 'mfg:capacity:view'),
  ('PLATFORM_ADMIN', 'capacity:subscribe'),
  ('CUSTOMER',       'capacity:subscribe')
ON CONFLICT (template_code, permission_code) DO NOTHING;

-- ---------- 8) 回写 roles.grants（权限真源）—— 沿用 V54 / V62 / V67 / V68 / V77 模式 ----------
UPDATE roles r
   SET grants = COALESCE(
       (SELECT to_jsonb(array_agg(tp.permission_code))::text
          FROM role_template_permissions tp
         WHERE tp.template_code = r.code),
       '[]')
 WHERE r.code IN ('MANUFACTURER', 'STATION');
