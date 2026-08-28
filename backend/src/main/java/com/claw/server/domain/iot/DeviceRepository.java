package com.claw.server.domain.iot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByImei(String imei);

    Optional<Device> findByDeviceNo(String deviceNo);

    List<Device> findByAssetId(Long assetId);
}
