package com.claw.server.domain.station;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** 库存出入库流水仓储（模块四 · 库存层）。 */
public interface StationInventoryMovementRepository extends JpaRepository<StationInventoryMovement, Long> {

    List<StationInventoryMovement> findByStationIdOrderByCreatedAtDesc(Long stationId);

    List<StationInventoryMovement> findByStationIdAndSkuCodeOrderByCreatedAtDesc(Long stationId, String skuCode);

    List<StationInventoryMovement> findByStationIdInOrderByCreatedAtDesc(List<Long> stationIds);

    /** 结算层"消耗数据源"：某站在时间窗内、delta_qty<0（消耗/回收）的流水。 */
    List<StationInventoryMovement> findByStationIdAndDeltaQtyLessThanAndCreatedAtBetween(
            Long stationId, Integer deltaQty, Instant start, Instant end);

    /**
     * V89：与上面同口径，但排除已归属履约订单的流水（fulfillment_order_id IS NOT NULL）。
     * 履约结算自身产生的库存扣减不再被服务站周期结算当成普通消耗重复计入，存量全 NULL 行不受影响。
     */
    List<StationInventoryMovement> findByStationIdAndDeltaQtyLessThanAndFulfillmentOrderIdIsNullAndCreatedAtBetween(
            Long stationId, Integer deltaQty, Instant start, Instant end);
}
