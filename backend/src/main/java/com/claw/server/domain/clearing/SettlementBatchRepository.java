package com.claw.server.domain.clearing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 结算批次仓储（claw.settlement_batch）。
 */
public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {

    Optional<SettlementBatch> findByBatchNo(String batchNo);

    List<SettlementBatch> findByStatusAndDeletedFalse(String status);

    List<SettlementBatch> findByBizSceneAndStatusAndDeletedFalse(String bizScene, String status);
}
