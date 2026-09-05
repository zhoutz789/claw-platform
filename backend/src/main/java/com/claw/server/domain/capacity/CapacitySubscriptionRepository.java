package com.claw.server.domain.capacity;

import com.claw.server.common.enums.CapacitySubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CapacitySubscriptionRepository extends JpaRepository<CapacitySubscription, Long> {

    List<CapacitySubscription> findByPlanIdAndDeletedFalse(Long planId);

    List<CapacitySubscription> findByPlanIdAndStatusAndDeletedFalse(Long planId, CapacitySubscriptionStatus status);

    List<CapacitySubscription> findBySubscriberUserIdAndDeletedFalse(Long subscriberUserId);

    Optional<CapacitySubscription> findByPlanIdAndSubscriberUserIdAndDeletedFalse(Long planId, Long subscriberUserId);

    boolean existsByPlanIdAndSubscriberUserIdAndDeletedFalse(Long planId, Long subscriberUserId);
}
