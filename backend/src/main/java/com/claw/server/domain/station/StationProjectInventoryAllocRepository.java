package com.claw.server.domain.station;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 项目占用库存仓储（模块四 · 项目层）。 */
public interface StationProjectInventoryAllocRepository extends JpaRepository<StationProjectInventoryAlloc, Long> {

    List<StationProjectInventoryAlloc> findByStationProjectId(Long projectId);

    List<StationProjectInventoryAlloc> findByStationStockId(Long stockId);

    Optional<StationProjectInventoryAlloc> findByStationProjectIdAndStationStockId(Long projectId, Long stockId);

    void deleteByStationProjectId(Long projectId);
}
