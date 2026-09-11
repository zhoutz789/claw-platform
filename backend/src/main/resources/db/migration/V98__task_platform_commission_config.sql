SET search_path = claw;

-- =====================================================================
-- 任务大厅平台佣金费率（system_config）
--
-- 默认 0：发布方把全额报酬支付给接单方，平台不抽成 —— 保持既有行为不变。
-- 由结算域 TaskSettlementService 读取；合法区间 [0,1]，
-- 缺失 / 空白 / 非数字 / 负数 / 大于 1 一律按 0 处理（费率 > 1 会让接单方所得为负）。
--
-- 注意：不复用既有 key `platform.fee.rate`（V17 已种为 '0.10'，非 0）。
-- 幂等：ON CONFLICT (config_key) DO NOTHING。
-- =====================================================================
INSERT INTO system_config (config_key, config_value, category, description, data_type, editable) VALUES
    ('TASK_HALL_PLATFORM_RATE', '0', '结算',
     '任务大厅平台佣金费率（占报酬比例）。0 表示平台不抽成、发布方全额支付给接单方；取值范围 [0,1]',
     'NUMBER', true)
ON CONFLICT (config_key) DO NOTHING;
