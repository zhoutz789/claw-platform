package com.claw.server.domain.operator;

import com.claw.server.common.enums.KycApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperatorKycRecordRepository extends JpaRepository<OperatorKycRecord, Long> {

    List<OperatorKycRecord> findByOperatorIdAndDeletedFalse(Long operatorId);

    List<OperatorKycRecord> findByStatusAndDeletedFalse(KycApprovalStatus status);
}
