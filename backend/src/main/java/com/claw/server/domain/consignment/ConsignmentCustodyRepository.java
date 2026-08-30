package com.claw.server.domain.consignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsignmentCustodyRepository extends JpaRepository<ConsignmentCustody, Long> {

    Optional<ConsignmentCustody> findByDeviceId(Long deviceId);

    List<ConsignmentCustody> findByManufacturerId(Long manufacturerId);

    List<ConsignmentCustody> findByHolderStationId(Long stationId);

    List<ConsignmentCustody> findByTransferOrderId(Long transferOrderId);
}
