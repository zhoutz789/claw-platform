package com.claw.server.domain.recovery;

import com.claw.server.common.enums.RecoveryOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecoveryOrderRepository extends JpaRepository<RecoveryOrder, Long> {

    Optional<RecoveryOrder> findByOrderNo(String orderNo);

    List<RecoveryOrder> findByOwnerUserIdAndDeletedFalse(Long ownerUserId);

    List<RecoveryOrder> findByStatusAndDeletedFalse(RecoveryOrderStatus status);
}
