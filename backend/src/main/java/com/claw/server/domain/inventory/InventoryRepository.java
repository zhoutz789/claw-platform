package com.claw.server.domain.inventory;

import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByDeviceId(Long deviceId);

    List<Inventory> findByOwnerManufacturerId(Long manufacturerId);

    List<Inventory> findByHolderStationId(Long stationId);

    List<Inventory> findByOwnershipType(OwnershipType ownershipType);

    List<Inventory> findByOwnerManufacturerIdAndOwnershipType(Long manufacturerId, OwnershipType ownershipType);

    List<Inventory> findByHolderStationIdAndOwnershipType(Long stationId, OwnershipType ownershipType);

    List<Inventory> findByCurrentStatus(LifecycleStatus status);
}
