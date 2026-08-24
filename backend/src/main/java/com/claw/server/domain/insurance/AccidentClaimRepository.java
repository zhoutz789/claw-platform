package com.claw.server.domain.insurance;

import com.claw.server.common.enums.ClaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccidentClaimRepository extends JpaRepository<AccidentClaim, Long> {

    Optional<AccidentClaim> findByClaimNo(String claimNo);

    List<AccidentClaim> findByInsuranceIdAndDeletedFalse(Long insuranceId);

    List<AccidentClaim> findByAssetIdAndDeletedFalse(Long assetId);

    List<AccidentClaim> findByStatusAndDeletedFalse(ClaimStatus status);
}
