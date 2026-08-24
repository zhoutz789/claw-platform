package com.claw.server.domain.recovery;

import com.claw.server.common.enums.ValuationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResidualValuationRepository extends JpaRepository<ResidualValuation, Long> {

    List<ResidualValuation> findByAssetIdAndDeletedFalse(Long assetId);

    List<ResidualValuation> findByOwnerUserIdAndDeletedFalse(Long ownerUserId);

    List<ResidualValuation> findByStatusAndDeletedFalse(ValuationStatus status);
}
