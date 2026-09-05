package com.claw.server.domain.capacity;

import com.claw.server.common.enums.CapacityPlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CapacityPlanRepository extends JpaRepository<CapacityPlan, Long> {

    Optional<CapacityPlan> findFirstByAssetIdAndDeletedFalseOrderByCreatedAtDesc(Long assetId);

    List<CapacityPlan> findByStatusAndDeletedFalse(CapacityPlanStatus status);

    List<CapacityPlan> findByOwnerUserIdAndDeletedFalse(Long ownerUserId);

    List<CapacityPlan> findByAssetIdAndStatusAndDeletedFalse(Long assetId, CapacityPlanStatus status);

    List<CapacityPlan> findByPoolEntryIdAndDeletedFalse(Long poolEntryId);
}
