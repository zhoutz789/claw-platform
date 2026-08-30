package com.claw.server.domain.recovery;

import com.claw.server.common.enums.RecoveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRecoveryOrderRepository extends JpaRepository<DeviceRecoveryOrder, Long> {

    Optional<DeviceRecoveryOrder> findByRecoveryNo(String recoveryNo);

    List<DeviceRecoveryOrder> findByManufacturerId(Long manufacturerId);

    List<DeviceRecoveryOrder> findByStationId(Long stationId);

    List<DeviceRecoveryOrder> findByStatus(RecoveryStatus status);
}
