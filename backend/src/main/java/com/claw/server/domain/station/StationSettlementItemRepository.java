package com.claw.server.domain.station;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 结算明细仓储（模块四 · 结算层）。 */
public interface StationSettlementItemRepository extends JpaRepository<StationSettlementItem, Long> {

    List<StationSettlementItem> findBySettlementIdOrderByCreatedAtAsc(Long settlementId);
}
