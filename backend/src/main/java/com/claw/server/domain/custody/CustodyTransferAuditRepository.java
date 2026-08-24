package com.claw.server.domain.custody;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustodyTransferAuditRepository extends JpaRepository<CustodyTransferAudit, Long> {

    List<CustodyTransferAudit> findByAssetIdAndDeletedFalse(Long assetId);

    List<CustodyTransferAudit> findByAnomalyTypeAndDeletedFalse(String anomalyType);

    List<CustodyTransferAudit> findByReviewedFalseAndDeletedFalse();

    List<CustodyTransferAudit> findByTransferIdAndDeletedFalse(Long transferId);
}
