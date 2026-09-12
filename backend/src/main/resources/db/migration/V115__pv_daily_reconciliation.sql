SET search_path = claw;

-- 光伏日对账（V115）：每天比对「全站逆变器累计发电量」与「并网点双向电表反向电量（下网=上网计量）」，
-- 两量长期偏差超阈值即告警——是光伏追溯数据对外交付「可信」的根基（串线接错 / 偷电 /
-- 逆变器离线未被发现 / 计量故障等风险都会表现为偏差）。
--
-- 口径铁律（与 PvGenerationHourly 对齐）：
--   INVERTER 与 METER 的 energy_wh 都是「该小时发电量差分」（已由累计计数器差分得到，口径已统一），
--   故对账直接按天 SUM(energy_wh) 比较；绝不做 cumulative_wh 的二次差分（那会重复计算）。
--     inverter_energy_wh      = SUM(energy_wh) WHERE source='INVERTER'
--     meter_reverse_energy_wh = SUM(energy_wh) WHERE source='METER'（下网=上网计量）
--     deviation_wh            = inverter - meter
--     deviation_rate          = deviation / meter（meter 为 0 记 NULL，不除零）
--     status                  = OK 偏差率≤阈值 / WARN 超阈值 / GAP 缺数据无法算
--
-- 阈值来自 system_config.PV_RECONCILE_DEVIATION_RATE（默认 0.05 = 5%），由服务层读取。

CREATE TABLE IF NOT EXISTS pv_daily_reconciliation (
    id                        BIGSERIAL PRIMARY KEY,
    station_asset_id          BIGINT NOT NULL,
    day                       DATE NOT NULL,
    inverter_energy_wh        NUMERIC(18,4),
    meter_reverse_energy_wh   NUMERIC(18,4),
    deviation_wh              NUMERIC(18,4),
    deviation_rate            NUMERIC(6,4),
    status                    VARCHAR(16) NOT NULL DEFAULT 'OK',
    note                      VARCHAR(255),
    tenant_id                 BIGINT NOT NULL DEFAULT 1,
    created_at                TIMESTAMP NOT NULL DEFAULT now()
);

-- 同一电站同一天只有一行（对账结果 upsert 语义，重复跑覆盖不插重复行）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_pv_daily_reconciliation_station_day
    ON pv_daily_reconciliation (station_asset_id, day);

-- 按状态筛（查所有 WARN/OK 便于看板与告警）。
CREATE INDEX IF NOT EXISTS idx_pv_daily_reconciliation_status
    ON pv_daily_reconciliation (status);

-- 按天扫（Job 跑某一天 / 历史回溯）。
CREATE INDEX IF NOT EXISTS idx_pv_daily_reconciliation_day
    ON pv_daily_reconciliation (day);
