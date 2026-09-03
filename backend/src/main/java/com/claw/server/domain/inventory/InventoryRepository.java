package com.claw.server.domain.inventory;

import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /** 模块三 · 按站点集合取数（厂家下属服务站 / 平台全量分组）。 */
    List<Inventory> findByHolderStationIdIn(Collection<Long> stationIds);

    /** 模块三 · 厂家按「所有权类型 + 下属站点集合」取寄售明细。 */
    List<Inventory> findByOwnerManufacturerIdAndOwnershipTypeAndHolderStationIdIn(
            Long ownerManufacturerId, OwnershipType ownershipType, Collection<Long> stationIds);

    /**
     * 模块三 · 厂家下属服务站反查（动态口径，零新表）。
     *
     * <p>「我的货现在寄在哪些站」 = distinct(holder_station_id)
     * where owner_manufacturer_id = :mfg and ownership_type = CONSIGNED and holder_station_id is not null。
     */
    @Query("SELECT DISTINCT i.holderStationId FROM Inventory i "
            + "WHERE i.ownerManufacturerId = :mfg AND i.ownershipType = :type "
            + "AND i.holderStationId IS NOT NULL")
    List<Long> findDistinctHolderStationIdsByManufacturer(
            @Param("mfg") Long manufacturerId, @Param("type") OwnershipType ownershipType);
}
