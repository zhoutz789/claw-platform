package com.claw.server.domain.pv;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 光伏日对账结果仓储（对应 claw.pv_daily_reconciliation）。
 *
 * <p>upsert 语义由调用方（{@link PvReconciliationService}）先按唯一键查询、
 * 命中则更新、未命中则插入实现；本接口仅暴露按电站+天的定位方法。
 */
public interface PvDailyReconciliationRepository extends JpaRepository<PvDailyReconciliation, Long> {

    /** 按唯一键 (station_asset_id, day) 定位当日对账行。 */
    Optional<PvDailyReconciliation> findByStationAssetIdAndDay(Long stationAssetId, LocalDate day);
}
