package com.claw.server.domain.insurance;

import com.claw.server.common.enums.InsuranceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VehicleInsuranceRepository extends JpaRepository<VehicleInsurance, Long> {

    Optional<VehicleInsurance> findByPolicyNo(String policyNo);

    Optional<VehicleInsurance> findByAssetIdAndStatusAndDeletedFalse(Long assetId, InsuranceStatus status);

    List<VehicleInsurance> findByOwnerUserIdAndDeletedFalse(Long ownerUserId);

    default Optional<VehicleInsurance> findActiveByAsset(Long assetId) {
        return findByAssetIdAndStatusAndDeletedFalse(assetId, InsuranceStatus.ACTIVE);
    }
}
