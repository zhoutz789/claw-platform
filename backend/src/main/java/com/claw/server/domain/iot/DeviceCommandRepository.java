package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, Long> {

    Optional<DeviceCommand> findByCmdId(String cmdId);

    List<DeviceCommand> findByDeviceNoOrderByCreatedAtDesc(String deviceNo);
}
