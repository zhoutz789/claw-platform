package com.claw.server.domain.clearing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 结算批次明细仓储（claw.settlement_batch_item）。
 */
public interface SettlementBatchItemRepository extends JpaRepository<SettlementBatchItem, Long> {

    List<SettlementBatchItem> findByBatchId(Long batchId);

    List<SettlementBatchItem> findByBatchIdAndStatus(Long batchId, String status);
}
