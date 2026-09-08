package com.claw.server.domain.capacity;

import com.claw.server.common.enums.CapacityPlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CapacityPlanRepository extends JpaRepository<CapacityPlan, Long> {

    Optional<CapacityPlan> findFirstByAssetIdAndDeletedFalseOrderByCreatedAtDesc(Long assetId);

    List<CapacityPlan> findByStatusAndDeletedFalse(CapacityPlanStatus status);

    List<CapacityPlan> findByOwnerUserIdAndDeletedFalse(Long ownerUserId);

    /** V81：按商品查容量计划（前端「容量预定」按钮按 productId 取，取第一条展示）。 */
    List<CapacityPlan> findByProductIdAndDeletedFalse(Long productId);

    List<CapacityPlan> findByAssetIdAndStatusAndDeletedFalse(Long assetId, CapacityPlanStatus status);

    List<CapacityPlan> findByPoolEntryIdAndDeletedFalse(Long poolEntryId);
}
