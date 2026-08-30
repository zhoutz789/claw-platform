package com.claw.server.domain.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransferOrderItemRepository extends JpaRepository<TransferOrderItem, Long> {

    List<TransferOrderItem> findByTransferOrderId(Long transferOrderId);

    Optional<TransferOrderItem> findByTransferOrderIdAndDeviceId(Long transferOrderId, Long deviceId);
}
