package com.claw.server.domain.transfer;

import com.claw.server.common.enums.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransferOrderRepository extends JpaRepository<TransferOrder, Long> {

    Optional<TransferOrder> findByTransferNo(String transferNo);

    List<TransferOrder> findByManufacturerId(Long manufacturerId);

    List<TransferOrder> findByFromStationId(Long fromStationId);

    List<TransferOrder> findByToStationId(Long toStationId);

    List<TransferOrder> findByStatus(TransferStatus status);
}
