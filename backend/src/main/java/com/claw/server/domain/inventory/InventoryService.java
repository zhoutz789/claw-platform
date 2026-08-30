package com.claw.server.domain.inventory;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.CustodyStatus;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.domain.consignment.ConsignmentCustody;
import com.claw.server.domain.consignment.ConsignmentCustodyRepository;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.lifecycle.LifecycleEvent;
import com.claw.server.domain.lifecycle.LifecycleEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 库存台账服务（增量 B · R3/B4）。
 *
 * <p>运营库存台账（inventory，每设备一行）与寄售占有权（consignment_custodies）1:1。
 * 本服务负责把设备从厂家自有库「发货至服务站」：建立寄售占有权 + 库存转为寄售在站（Q2 占有权转移点）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ConsignmentCustodyRepository custodyRepository;
    private final DeviceRepository deviceRepository;
    private final LifecycleEventRepository lifecycleRepository;

    /** 发货至服务站：建立寄售占有权（consignment_custodies），库存转为寄售在站（Q2 占有权转移）。 */
    @Transactional
    public void shipToStation(Long deviceId, Long stationId, Long manufacturerId, Long operatorId) {
        Inventory inv = inventoryRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
        ConsignmentCustody custody = custodyRepository.findByDeviceId(deviceId)
                .orElseGet(() -> custodyRepository.save(ConsignmentCustody.builder()
                        .deviceId(deviceId)
                        .manufacturerId(manufacturerId)
                        .holderStationId(stationId)
                        .status(CustodyStatus.ACTIVE)
                        .liabilityHolder("MANUFACTURER")
                        .build()));
        custody.setHolderStationId(stationId);
        custody.setManufacturerId(manufacturerId);
        custody.setStatus(CustodyStatus.ACTIVE);
        custody.setLiabilityHolder("MANUFACTURER");
        custody.setTransferredAt(null);
        custody.setTransferOrderId(null);
        custodyRepository.save(custody);

        inv.setOwnershipType(OwnershipType.CONSIGNED);
        inv.setHolderStationId(stationId);
        inv.setCustodyId(custody.getId());
        inv.setCurrentStatus(LifecycleStatus.AT_STATION);
        inv.setInboundAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        inventoryRepository.save(inv);

        deviceRepository.findById(deviceId).ifPresent(d -> {
            d.setLifecycleStatus(LifecycleStatus.AT_STATION.name());
            deviceRepository.save(d);
        });
        lifecycleRepository.save(LifecycleEvent.builder()
                .deviceId(deviceId)
                .fromStatus(LifecycleStatus.PRODUCING.name())
                .toStatus(LifecycleStatus.AT_STATION.name())
                .eventType("RECEIVE")
                .operatorId(operatorId)
                .stationId(stationId)
                .occurredAt(Instant.now())
                .build());
        log.info("设备 {} 发货至服务站 {}（建寄售占有权）", deviceId, stationId);
    }

    @Transactional(readOnly = true)
    public List<Inventory> listByManufacturer(Long manufacturerId) {
        return inventoryRepository.findByOwnerManufacturerId(manufacturerId);
    }

    @Transactional(readOnly = true)
    public List<Inventory> listByStation(Long stationId) {
        return inventoryRepository.findByHolderStationId(stationId);
    }

    @Transactional(readOnly = true)
    public List<Inventory> listByOwnership(OwnershipType ownershipType) {
        return inventoryRepository.findByOwnershipType(ownershipType);
    }

    @Transactional(readOnly = true)
    public Inventory getByDevice(Long deviceId) {
        return inventoryRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "inventory.not.found"));
    }
}
