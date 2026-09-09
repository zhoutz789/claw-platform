-- =====================================================================
-- Claw 平台 V89 增量（库存消耗流水 ↔ 履约订单关联，隔离结算扫描口径）
--
-- 背景：StationSettlementService.generate 扫描某站时间窗内 delta_qty<0 的
--       station_inventory_movements 作为「消耗数据源」算物流费/提成。履约结算也会产生
--       同表的扣减流水（如履约发货/取货对应的库存变动），若不区分，会被存量服务站结算
--       当成普通消耗重复计入，造成金额错算。
--
-- 范围：
--   1) station_inventory_movements 加两列：fulfillment_order_id / fulfillment_settlement_id；
--   2) 修改 StationSettlementService.generate 的扫描查询，追加
--      `AND fulfillment_order_id IS NULL`（见 StationSettlementService.java 与
--      StationInventoryMovementRepository.java 的新派生方法）。该条件对存量全 NULL 的列
--      恒真 →<b>存量行为必须完全不变</b>，本次只是把「履约产生的流水」从「服务站周期结算」
--      口径中排除，不影响任何已上线功能。
--
-- ⚠️ 陷阱：表名不带 schema 前缀（顶部 SET search_path = claw）。
--   fulfillment_order_id 全量 NULL 时 `IS NULL` 过滤恒真，对既有结算查询零影响。
-- ⚠️ 幂等：ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS，二次执行安全。
-- =====================================================================

SET search_path = claw;

ALTER TABLE station_inventory_movements ADD COLUMN IF NOT EXISTS fulfillment_order_id    BIGINT;
ALTER TABLE station_inventory_movements ADD COLUMN IF NOT EXISTS fulfillment_settlement_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_sim_fulfillment_order
    ON station_inventory_movements (fulfillment_order_id)
    WHERE fulfillment_order_id IS NOT NULL;
