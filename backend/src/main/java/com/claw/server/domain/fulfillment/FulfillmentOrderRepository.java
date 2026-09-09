package com.claw.server.domain.fulfillment;

import com.claw.server.common.enums.FulfillmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FulfillmentOrderRepository extends JpaRepository<FulfillmentOrder, Long> {

    Optional<FulfillmentOrder> findByOrderNo(String orderNo);

    /** 悲观锁读取（结算并发双结串行化用）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM FulfillmentOrder o WHERE o.id = ?1")
    Optional<FulfillmentOrder> findByIdForUpdate(Long id);

    List<FulfillmentOrder> findByCustomerUserId(Long customerUserId);

    List<FulfillmentOrder> findByStationId(Long stationId);

    List<FulfillmentOrder> findByManufacturerId(Long manufacturerId);

    List<FulfillmentOrder> findByStatus(FulfillmentStatus status);

    /** 超时扫描（Q1 订单分支）：状态仍待履约且下单时点早于阈值（expire_at 之前）。 */
    List<FulfillmentOrder> findByStatusAndExpireAtBefore(FulfillmentStatus status, Instant expireAt);
}
