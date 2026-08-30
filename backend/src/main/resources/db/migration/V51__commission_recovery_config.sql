-- =====================================================================
-- Claw 平台 V51 增量（设备销售提成 + 回收 + 可配项 · R7/R8/B10 增量 B）
-- 依据：增量设计-权限骨架与库存流转域.md（V51 提成规则 + 回收 + 配置）
-- 范围：
--   · device_sales_commission_rules（设备销售提成规则，与光伏 revenue_split_rules 解耦）
--   · device_recovery_orders（设备回收单；既有 recovery_orders 表为车辆残值回收，不混用，故新表）
--   · system_config 种子：RECOVERY_DAYS / FULFILL_TIMEOUT_DAYS / STATION_ASSIGN_MODE（表已存在 V17，仅补种子）
-- 全量幂等。
-- =====================================================================

SET search_path = claw;

-- 1. 设备销售提成规则（与光伏分成解耦）
CREATE TABLE IF NOT EXISTS device_sales_commission_rules (
    id            BIGSERIAL PRIMARY KEY,
    manufacturer_id BIGINT      REFERENCES manufacturers (id),    -- NULL=平台默认规则
    product_id     BIGINT       REFERENCES products (id),         -- NULL=该厂家全部商品
    rule_name      VARCHAR(80),
    commission_type VARCHAR(20) NOT NULL,                         -- RATE（比例）/ AMOUNT（定额）
    rate           NUMERIC(6,4),                                  -- 如 0.08
    amount         NUMERIC(12,2),                                 -- 定额
    min_amount     NUMERIC(12,2),
    max_amount     NUMERIC(12,2),                                 -- 封顶/保底
    priority       INT          NOT NULL DEFAULT 0,               -- 多规则命中取高优先级
    effective_from TIMESTAMPTZ,
    effective_to   TIMESTAMPTZ,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by     BIGINT       REFERENCES users (id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_dscr_match ON device_sales_commission_rules (manufacturer_id, product_id, enabled);

-- 2. 设备回收单（与车辆残值回收 recovery_orders 解耦）
CREATE TABLE IF NOT EXISTS device_recovery_orders (
    id             BIGSERIAL PRIMARY KEY,
    recovery_no     VARCHAR(40) NOT NULL UNIQUE,
    manufacturer_id BIGINT       NOT NULL REFERENCES manufacturers (id),  -- 发起方
    station_id      BIGINT       NOT NULL REFERENCES stations (id),        -- 原寄售站
    asset_id        BIGINT       NOT NULL REFERENCES assets (id),
    reason          VARCHAR(40),                                          -- UNSOLD_TIMEOUT/FULFILL_TIMEOUT/MANUAL
    trigger_type    VARCHAR(20) NOT NULL DEFAULT 'MANUAL',                -- AUTO/MANUAL
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',               -- PENDING/CONFIRMED/MARKED/RETURNED/CANCELLED
    inbound_at      TIMESTAMPTZ,                                          -- 回流厂家自有库时点
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_dro_no     ON device_recovery_orders (recovery_no);
CREATE INDEX IF NOT EXISTS idx_dro_mfg    ON device_recovery_orders (manufacturer_id);
CREATE INDEX IF NOT EXISTS idx_dro_status ON device_recovery_orders (status);

-- 3. 系统可配项种子（回收/履约超时默认 90 天，可配 B10）
INSERT INTO system_config (config_key, config_value, category, description, data_type, editable) VALUES
    ('RECOVERY_DAYS',        '90', '库存', '寄售设备未成交/未履约回收超时天数（从入寄售库时点起算，Q1）', 'NUMBER', true),
    ('FULFILL_TIMEOUT_DAYS', '90', '订单', '待履约订单履约超时天数（从下单起算，超时取消释放冻结，Q1）', 'NUMBER', true),
    ('STATION_ASSIGN_MODE',  'USER_FIXED', '订单', '缺货远程代下单落点模式：USER_FIXED=用户指定服务站 / SYSTEM_RECOMMEND=系统就近', 'STRING', true)
ON CONFLICT (config_key) DO NOTHING;
