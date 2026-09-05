package com.claw.server.domain.capacity;

import com.claw.server.common.enums.CapacitySubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CapacitySubscriptionRepository extends JpaRepository<CapacitySubscription, Long> {

    List<CapacitySubscription> findByPlanIdAndDeletedFalse(Long planId);

    List<CapacitySubscription> findByPlanIdAndStatusAndDeletedFalse(Long planId, CapacitySubscriptionStatus status);

    List<CapacitySubscription> findBySubscriberUserIdAndDeletedFalse(Long subscriberUserId);

    Optional<CapacitySubscription> findByPlanIdAndSubscriberUserIdAndDeletedFalse(Long planId, Long subscriberUserId);

    boolean existsByPlanIdAndSubscriberUserIdAndDeletedFalse(Long planId, Long subscriberUserId);

    /**
     * 某订户在指定计划下已定购的份数合计（PARALLEL 并行共享语义下用于 top-up 容量校验）。
     */
    @Query("select coalesce(sum(c.unitCount),0) from CapacitySubscription c "
            + "where c.planId = ?1 and c.subscriberUserId = ?2 and c.deleted = false")
    Long sumUnitCountByPlanIdAndSubscriberUserId(Long planId, Long subscriberUserId);
}
