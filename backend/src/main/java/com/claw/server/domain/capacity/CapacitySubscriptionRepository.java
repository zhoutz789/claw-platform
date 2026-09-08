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

    /**
     * V84：同一订户在同一计划下<b>唯一的一行 ACTIVE 定购</b>。
     *
     * <p>对应 V71 建的部分唯一索引 {@code uq_cap_sub_active}
     * （{@code UNIQUE (plan_id, subscriber_user_id) WHERE deleted = FALSE AND status = 'ACTIVE'}）。
     * 「一订户一行、重复预定累加」语义下，重复预定必须先定位到这一行做累加，
     * 而不是再插一行（后者必然撞唯一索引）。
     *
     * <p>与既有的 {@link #findByPlanIdAndSubscriberUserIdAndDeletedFalse} 区别：那个不带
     * status 条件，会把已 REFUNDED / CANCELLED 的历史行也捞出来，不能用于累加判定。
     */
    Optional<CapacitySubscription> findByPlanIdAndSubscriberUserIdAndStatusAndDeletedFalse(
            Long planId, Long subscriberUserId, CapacitySubscriptionStatus status);

    boolean existsByPlanIdAndSubscriberUserIdAndDeletedFalse(Long planId, Long subscriberUserId);

    /**
     * 某订户在指定计划下已定购的份数合计（PARALLEL 并行共享语义下用于 top-up 容量校验）。
     */
    @Query("select coalesce(sum(c.unitCount),0) from CapacitySubscription c "
            + "where c.planId = ?1 and c.subscriberUserId = ?2 and c.deleted = false")
    Long sumUnitCountByPlanIdAndSubscriberUserId(Long planId, Long subscriberUserId);
}
