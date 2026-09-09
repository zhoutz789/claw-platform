-- =====================================================================
-- Claw 平台 V87 增量（履约结算台账：资金字段补全 + 唯一约束 + 恒等式校验）
--
-- 背景：V50 已建 fulfillment_settlements（物流费 / 服务站提成 / 余额归厂家），
--       但缺「订单总金额」「扣款费率」「命中规则」「收款户」「挂起原因」等结算审计字段，
--       且一个订单只能成功结算一次（幂等）缺乏 DB 层强制约束。V86 之后由
--       FulfillmentSettlementService（实现 OutboxHandler）在 PICKUP_COMPLETED 事件
--       触发时异步结算，必须保证「同一 fulfillment_order_id 仅一笔成功结算」。
--
-- 范围（只加列 / 约束，不删列、不改既有语义）：
--   1) 补全结算审计/金额字段（total_amount / logistics_fee_rate / commission_rule_* /
--      customer_user_id / currency / source_event_id / reason_code / fail_reason /
--      retry_count / settled_at / handled_by / handled_at / handle_remark）；
--   2) 加 UNIQUE(fulfillment_order_id)，强制一单一结算；
--   3) 加恒等式 CHECK：状态为 SETTLED/DONE 时，commission_amount + balance_to_mfg = total_amount
--      （「服务站提成 + 厂家货款 = 订单总额」必须一分不差，这是资金准确的硬约束）。
--
-- ⚠️ 陷阱：本脚本顶部已 SET search_path = claw，与 V50/V86 一致，表名严禁加 "claw." 前缀。
--   settlement_no 在 V50 已建 UNIQUE，此处<b>不得重复加</b>，否则报重复约束。
-- ⚠️ 精度：total_amount 直接建为 NUMERIC(18,4)；遗留的 logistics_fee/commission_amount/
--   balance_to_mfg 由 V88 统一放宽到 NUMERIC(18,4)，避免恒等式在 DB 侧因 12,2 精度丢失而失败。
-- ⚠️ 幂等：ADD COLUMN IF NOT EXISTS / DROP+ADD CONSTRAINT，二次执行无副作用。
-- =====================================================================

SET search_path = claw;

-- ---------- 1) 结算审计 / 金额字段补全 ----------
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS total_amount         NUMERIC(18,4) DEFAULT 0;
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS logistics_fee_rate   NUMERIC(18,4) DEFAULT 0;
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS commission_rule_id   BIGINT;
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS commission_rule_snapshot TEXT;
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS customer_user_id     BIGINT;
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS currency             CHAR(3)      DEFAULT 'USD';
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS source_event_id      BIGINT;
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS reason_code          VARCHAR(40);
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS fail_reason          VARCHAR(512);
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS retry_count          INT          DEFAULT 0;
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS settled_at           TIMESTAMPTZ;
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS handled_by           BIGINT;
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS handled_at           TIMESTAMPTZ;
ALTER TABLE fulfillment_settlements ADD COLUMN IF NOT EXISTS handle_remark        VARCHAR(512);

-- ---------- 2) 一订单一结算（幂等硬约束）----------
-- settlement_no 在 V50 已是 UNIQUE，此处只约束业务主键 fulfillment_order_id。
ALTER TABLE fulfillment_settlements DROP CONSTRAINT IF EXISTS uq_fulfillment_order_id;
ALTER TABLE fulfillment_settlements
    ADD CONSTRAINT uq_fulfillment_order_id UNIQUE (fulfillment_order_id);

-- ---------- 3) 恒等式 CHECK：结算成功时金额必须守恒 ----------
-- commission_amount + balance_to_mfg 必须等于 total_amount（服务站提成 + 厂家货款 = 订单总额）。
-- 仅对终态（SETTLED / DONE）校验；挂起（MANUAL/FAILED/PENDING）允许金额未齐。
ALTER TABLE fulfillment_settlements DROP CONSTRAINT IF EXISTS ck_fs_identity;
ALTER TABLE fulfillment_settlements
    ADD CONSTRAINT ck_fs_identity
    CHECK (status NOT IN ('SETTLED', 'DONE')
           OR (commission_amount + balance_to_mfg = total_amount));
