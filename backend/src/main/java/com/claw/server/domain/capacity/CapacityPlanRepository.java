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

    /** 按资产查全部未删除计划（切片 4a 无人机收益报告：PARALLEL 回佣聚合用，不限状态）。 */
    List<CapacityPlan> findByAssetIdAndDeletedFalse(Long assetId);

    List<CapacityPlan> findByPoolEntryIdAndDeletedFalse(Long poolEntryId);
}
