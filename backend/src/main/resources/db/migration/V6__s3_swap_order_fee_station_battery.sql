-- =====================================================================
-- Claw 平台 V6 增量表（S3：换电域）
-- 依据：《技术开发文档 v0.4》2.3 换电订单状态机 + 2.4 押金流转原子事务
--        + 3.2 API 清单 + PRD v1.1 4.2/4.3（实缴实结 + 按度计价 + 锁版费率）
-- 范围：
--   · fee_rules 锁版费率（光伏 0.12 / 市电 0.18 / 换电服务费 0.32 三拆）
--   · swap_orders 换电订单（CREATED→FROZEN→SWAPPING→SETTLED）
--   · order_events 订单事件流（审计/可回溯）
--   · station_batteries 换电站电池位（满电/充电中/出库）
--   · 种子：9 块电池资产（3 站 × 3 块，FIFO 押金差异）+ 电池位 + 锁版费率
-- 通用规范继承 V1：schema claw、tenant_id、deleted、时间戳
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 锁版费率表（按度计价，生效区间版本化）
--    电费：光伏 $0.12/度（投资者50%/场地方20%/平台30%）、市电 $0.18/度（EDC 原价转付）
--    服务费：$0.32/度 = 电池折旧基金 $0.10 + 站经营分成 $0.19（90%先发+10%考核返还）+ 平台 $0.03
-- ---------------------------------------------------------------------
CREATE TABLE claw.fee_rules (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_code      VARCHAR(32) NOT NULL UNIQUE,   -- PV_ELEC 光伏电费 | GRID_ELEC 市电费 | SWAP_SERVICE 换电服务费
    name           VARCHAR(64) NOT NULL,
    unit           VARCHAR(16) NOT NULL DEFAULT 'kWh',
    price          NUMERIC(10,4) NOT NULL,        -- $/kWh（锁版价）
    share_json     JSONB        NOT NULL DEFAULT '{}',  -- 分账拆解（百分比/固定额）
    effective_from DATE         NOT NULL DEFAULT CURRENT_DATE,
    effective_to   DATE,
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 2. 换电订单（domain.swap）
--    状态机：CREATED(选电池/扫码) → FROZEN(押金+预扣冻结成功)
--            → SWAPPING(出满电/收欠电, 双向押金流转事务)
--            → SETTLED(按实际用量结算, 多退少补)
--            任意态 → EXCEPTION(人工介入) / CANCELLED(解冻退回)
-- ---------------------------------------------------------------------
CREATE TABLE claw.swap_orders (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_no            VARCHAR(64) NOT NULL UNIQUE,
    user_id             BIGINT      NOT NULL REFERENCES claw.users (id),
    station_id          BIGINT      NOT NULL REFERENCES claw.stations (id),
    vehicle_id          BIGINT      REFERENCES claw.assets (id),     -- 可选：车辆（协议匹配）
    battery_out_id      BIGINT      REFERENCES claw.assets (id),     -- 满电电池（出）
    battery_in_id       BIGINT      REFERENCES claw.assets (id),     -- 欠电电池（收，可为空=首次换电）
    status              VARCHAR(16) NOT NULL DEFAULT 'CREATED',      -- CREATED/FROZEN/SWAPPING/SETTLED/EXCEPTION/CANCELLED
    protocol_ver        VARCHAR(16),                                 -- 换电协议版本（B_new 与车辆匹配校验）
    battery_deposit     NUMERIC(16,2) NOT NULL DEFAULT 0,            -- 新电池押金（冻结，动态残值）
    old_battery_deposit NUMERIC(16,2) NOT NULL DEFAULT 0,            -- 旧电池押金（confirm 退还用户）
    est_kwh             NUMERIC(8,2)  NOT NULL DEFAULT 2.00,         -- 预估度数（2 度基准）
    est_elec_fee        NUMERIC(16,2) NOT NULL DEFAULT 0,            -- 预估电费
    est_service_fee     NUMERIC(16,2) NOT NULL DEFAULT 0,            -- 预估服务费
    est_total           NUMERIC(16,2) NOT NULL DEFAULT 0,            -- 预估合计（预扣额）
    actual_kwh          NUMERIC(8,2),                                -- 实际用量（结算回填，BMS 实报）
    actual_elec_fee     NUMERIC(16,2),
    actual_service_fee  NUMERIC(16,2),
    actual_total        NUMERIC(16,2),
    soc_start           NUMERIC(5,2),                                -- 起始电量 %（满电=100）
    soc_end             NUMERIC(5,2),                                -- 结束电量 %（归还时 BMS 上报）
    price_snapshot      JSONB,                                       -- 锁定快照：{elecRate, serviceRate, serviceSplit}
    settle_status       VARCHAR(16),                                 -- PREAUTHED 已预扣 | SETTLED 已结算 | REFUNDED 已退差
    cancel_reason       VARCHAR(255),
    tenant_id           BIGINT      NOT NULL DEFAULT 1,
    deleted             BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_swap_orders_user   ON claw.swap_orders (user_id, created_at DESC);
CREATE INDEX idx_swap_orders_status ON claw.swap_orders (status, created_at DESC);

-- ---------------------------------------------------------------------
-- 3. 订单事件流（审计/可回溯，技术文档 2.3 状态机每次流转写事件）
-- ---------------------------------------------------------------------
CREATE TABLE claw.order_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_no    VARCHAR(64) NOT NULL REFERENCES claw.swap_orders (order_no),
    event       VARCHAR(32) NOT NULL,            -- CREATED/FROZEN/SWAPPING/SETTLED/CANCELLED/EXCEPTION
    operator_id BIGINT,
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_events_order ON claw.order_events (order_no, created_at ASC);

-- ---------------------------------------------------------------------
-- 4. 换电站电池位（技术文档 2.4 步骤 5：B_old → 充电位, B_new → 出库）
--    满电电池数 = 换电站可用供给（地图适配层数据源）
-- ---------------------------------------------------------------------
CREATE TABLE claw.station_batteries (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    station_id  BIGINT      NOT NULL REFERENCES claw.stations (id),
    battery_id  BIGINT      NOT NULL REFERENCES claw.assets (id),
    slot_no     INT         NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'CHARGING',  -- READY 满电可换 | CHARGING 充电中 | OUT 出库（被用户持有）
    soc         NUMERIC(5,2) NOT NULL DEFAULT 100.00,
    tenant_id   BIGINT      NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (station_id, slot_no),
    UNIQUE (battery_id)
);

CREATE INDEX idx_station_batteries_ready ON claw.station_batteries (station_id, status);

-- ---------------------------------------------------------------------
-- 5. 种子：锁版费率（对齐 PRD 4.2 费用标准）
-- ---------------------------------------------------------------------
INSERT INTO claw.fee_rules (rule_code, name, unit, price, share_json) VALUES
    ('PV_ELEC',      '光伏供电电费', 'kWh', 0.12, '{"investor_pct":0.50,"site_pct":0.20,"platform_pct":0.30}'),
    ('GRID_ELEC',    '市电电费(EDC)', 'kWh', 0.18, '{"edc_passthrough":true}'),
    ('SWAP_SERVICE', '换电服务费',   'kWh', 0.32, '{"battery_fund":0.10,"station":0.19,"platform":0.03}');

-- ---------------------------------------------------------------------
-- 6. 种子：9 块电池资产（3 站 × 3 块，押金=FIFO 动态残值）
--    protocol_ver='P1' 统一，演示协议匹配通过
-- ---------------------------------------------------------------------
INSERT INTO claw.assets (asset_type, asset_no, qr_code, status) VALUES
    ('battery', 'BAT-PP-001', 'QR-BAT-001', 'IN_STOCK'),
    ('battery', 'BAT-PP-002', 'QR-BAT-002', 'IN_STOCK'),
    ('battery', 'BAT-PP-003', 'QR-BAT-003', 'IN_STOCK'),
    ('battery', 'BAT-PP-004', 'QR-BAT-004', 'IN_STOCK'),
    ('battery', 'BAT-PP-005', 'QR-BAT-005', 'IN_STOCK'),
    ('battery', 'BAT-PP-006', 'QR-BAT-006', 'IN_STOCK'),
    ('battery', 'BAT-PP-007', 'QR-BAT-007', 'IN_STOCK'),
    ('battery', 'BAT-PP-008', 'QR-BAT-008', 'IN_STOCK'),
    ('battery', 'BAT-PP-009', 'QR-BAT-009', 'IN_STOCK');

INSERT INTO claw.batteries (asset_id, model, capacity_kwh, protocol_ver, soh, cycle_count, deposit_value)
SELECT a.id, v.model, 2.00, 'P1', 100.00, v.cyc, v.dep
FROM claw.assets a JOIN (VALUES
    ('BAT-PP-001','Yadea-48V30Ah', 0, 40.00),
    ('BAT-PP-002','Yadea-48V30Ah', 0, 40.00),
    ('BAT-PP-003','Yadea-48V30Ah', 0, 40.00),
    ('BAT-PP-004','Yadea-48V30Ah', 8, 38.00),
    ('BAT-PP-005','Yadea-48V30Ah', 8, 38.00),
    ('BAT-PP-006','Yadea-48V30Ah', 8, 38.00),
    ('BAT-PP-007','Yadea-48V30Ah', 20, 36.00),
    ('BAT-PP-008','Yadea-48V30Ah', 20, 36.00),
    ('BAT-PP-009','Yadea-48V30Ah', 20, 36.00)
) AS v(no, model, cyc, dep) ON a.asset_no = v.no;

-- 电池位：3 站 × 3 槽（满电 READY），对齐 UI 原型金边 3 站
INSERT INTO claw.station_batteries (station_id, battery_id, slot_no, status, soc)
SELECT s.id, a.id, v.slot, 'READY', 100.00
FROM (VALUES
    ('PP-ROUSSEY','BAT-PP-001',1), ('PP-ROUSSEY','BAT-PP-002',2), ('PP-ROUSSEY','BAT-PP-003',3),
    ('PP-CENTRAL','BAT-PP-004',1), ('PP-CENTRAL','BAT-PP-005',2), ('PP-CENTRAL','BAT-PP-006',3),
    ('PP-AIRPORT','BAT-PP-007',1), ('PP-AIRPORT','BAT-PP-008',2), ('PP-AIRPORT','BAT-PP-009',3)
) AS v(site, battery_no, slot)
JOIN claw.stations s ON s.code = v.site
JOIN claw.assets a ON a.asset_no = v.battery_no;
