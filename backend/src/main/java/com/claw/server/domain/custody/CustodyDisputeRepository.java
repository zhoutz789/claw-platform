package com.claw.server.domain.custody;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustodyDisputeRepository extends JpaRepository<CustodyDispute, Long> {

    List<CustodyDispute> findByStatusAndDeletedFalse(String status);

    List<CustodyDispute> findByClaimantIdAndDeletedFalse(Long claimantId);
}
