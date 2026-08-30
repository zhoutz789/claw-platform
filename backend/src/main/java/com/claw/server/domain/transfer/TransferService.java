package com.claw.server.domain.transfer;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.CustodyStatus;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.TransferStatus;
import com.claw.server.domain.consignment.ConsignmentCustody;
import com.claw.server.domain.consignment.ConsignmentCustodyRepository;
import com.claw.server.domain.inventory.Inventory;
import com.claw.server.domain.inventory.InventoryRepository;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.lifecycle.LifecycleEvent;
import com.claw.server.domain.lifecycle.LifecycleEventRepository;
import com.claw.server.domain.station.StationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 站间调拨服务（增量 B · R5/B7）。
 *
 * <p>厂家发起调拨单，将寄售设备从源服务站调拨到目标服务站；扫码交接时占有权随
 * {@code ConsignmentCustody} 转移（Q2）：源站交接（handover）→ 在途（LOGISTICS 责任）→
 * 目标站收货（receive）建新占有权。库存台账（inventory）同步更新持有服务站与状态。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private final TransferOrderRepository transferRepository;
    private final TransferOrderItemRepository transferItemRepository;
    private final ConsignmentCustodyRepository custodyRepository;
    private final InventoryRepository inventoryRepository;
    private final DeviceRepository deviceRepository;
    private final LifecycleEventRepository lifecycleRepository;
    private final StationRepository stationRepository;

    @Transactional
    public TransferOrder createTransfer(Long manufacturerId, Long fromStationId, Long toStationId,
                                       List<Long> deviceIds, BigDecimal logisticsFee, Long operatorId) {
        if (stationRepository.findById(fromStationId).isEmpty()) {
            throw BizException.of(40401, "station.not.found");
        }
        if (stationRepository.findById(toStationId).isEmpty()) {
            throw BizException.of(40401, "station.not.found");
        }
        TransferOrder o = TransferOrder.builder()
                .transferNo("TR" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 16))
                .manufacturerId(manufacturerId)
                .fromStationId(fromStationId)
                .toStationId(toStationId)
                .status(TransferStatus.DRAFT)
                .logisticsFee(logisticsFee)
                .createdBy(operatorId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        o = transferRepository.save(o);
        for (Long devId : deviceIds) {
            ConsignmentCustody from = custodyRepository.findByDeviceId(devId)
                    .orElseThrow(() -> BizException.of(40401, "custody.not.found"));
            if (!from.getHolderStationId().equals(fromStationId)) {
                throw BizException.of(40912, "custody.not.at.from.station");
            }
            transferItemRepository.save(TransferOrderItem.builder()
                    .transferOrderId(o.getId())
                    .deviceId(devId)
                    .fromCustodyId(from.getId())
                    .build());
        }
        return o;
    }

    /** 源站扫码交接：源占有权转出（TRANSFERRED_OUT），在途责任转 LOGISTICS；库存转 IN_TRANSIT。 */
    @Transactional
    public TransferOrder handover(Long transferId, Long operatorId) {
        TransferOrder o = load(transferId);
        if (o.getStatus() != TransferStatus.DRAFT && o.getStatus() != TransferStatus.CREATED) {
            throw BizException.of(40913, "transfer.not.handoverable");
        }
        for (TransferOrderItem item : transferItemRepository.findByTransferOrderId(transferId)) {
            ConsignmentCustody from = custodyRepository.findById(item.getFromCustodyId())
                    .orElseThrow(() -> BizException.of(40401, "custody.not.found"));
            from.setStatus(CustodyStatus.TRANSFERRED_OUT);
            from.setTransferredAt(Instant.now());
            from.setLiabilityHolder("LOGISTICS");
            from.setTransferOrderId(transferId);
            custodyRepository.save(from);
            updateInventoryStatus(item.getDeviceId(), LifecycleStatus.IN_TRANSIT, null);
        }
        o.setStatus(TransferStatus.IN_TRANSIT);
        o.setHandoverAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return transferRepository.save(o);
    }

    /** 目标站扫码收货：建新占有权（持目标站），源占有权结束；库存转 AT_STATION。 */
    @Transactional
    public TransferOrder receive(Long transferId, Long operatorId) {
        TransferOrder o = load(transferId);
        if (o.getStatus() != TransferStatus.IN_TRANSIT) {
            throw BizException.of(40914, "transfer.not.in.transit");
        }
        for (TransferOrderItem item : transferItemRepository.findByTransferOrderId(transferId)) {
            ConsignmentCustody from = custodyRepository.findById(item.getFromCustodyId())
                    .orElseThrow(() -> BizException.of(40401, "custody.not.found"));
            ConsignmentCustody to = custodyRepository.save(ConsignmentCustody.builder()
                    .deviceId(item.getDeviceId())
                    .manufacturerId(o.getManufacturerId())
                    .holderStationId(o.getToStationId())
                    .status(CustodyStatus.ACTIVE)
                    .liabilityHolder("STATION")
                    .transferOrderId(transferId)
                    .build());
            from.setEndedAt(Instant.now());
            from.setEndedReason("TRANSFERRED");
            custodyRepository.save(from);

            inventoryRepository.findByDeviceId(item.getDeviceId()).ifPresent(inv -> {
                inv.setHolderStationId(o.getToStationId());
                inv.setCustodyId(to.getId());
                inv.setCurrentStatus(LifecycleStatus.AT_STATION);
                inv.setUpdatedAt(Instant.now());
                inventoryRepository.save(inv);
            });
            deviceRepository.findById(item.getDeviceId()).ifPresent(d -> {
                d.setLifecycleStatus(LifecycleStatus.AT_STATION.name());
                deviceRepository.save(d);
            });
            lifecycleRepository.save(LifecycleEvent.builder()
                    .deviceId(item.getDeviceId())
                    .fromStatus(LifecycleStatus.IN_TRANSIT.name())
                    .toStatus(LifecycleStatus.AT_STATION.name())
                    .eventType("TRANSFER_IN")
                    .operatorId(operatorId)
                    .stationId(o.getToStationId())
                    .custodyRef(to.getId())
                    .occurredAt(Instant.now())
                    .build());
        }
        o.setStatus(TransferStatus.COMPLETED);
        o.setReceiveAt(Instant.now());
        o.setCompletedAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return transferRepository.save(o);
    }

    @Transactional(readOnly = true)
    public List<TransferOrder> listTransfers(Long manufacturerId) {
        return (manufacturerId == null)
                ? transferRepository.findAll()
                : transferRepository.findByManufacturerId(manufacturerId);
    }

    @Transactional(readOnly = true)
    public TransferOrder getTransfer(Long id) {
        return load(id);
    }

    private TransferOrder load(Long id) {
        return transferRepository.findById(id)
                .orElseThrow(() -> BizException.of(40401, "transfer.order.not.found"));
    }

    private void updateInventoryStatus(Long deviceId, LifecycleStatus status, Long stationId) {
        inventoryRepository.findByDeviceId(deviceId).ifPresent(inv -> {
            inv.setCurrentStatus(status);
            inv.setUpdatedAt(Instant.now());
            inventoryRepository.save(inv);
        });
        deviceRepository.findById(deviceId).ifPresent(d -> {
            d.setLifecycleStatus(status.name());
            deviceRepository.save(d);
        });
        lifecycleRepository.save(LifecycleEvent.builder()
                .deviceId(deviceId)
                .toStatus(status.name())
                .eventType("TRANSFER_OUT")
                .stationId(stationId)
                .occurredAt(Instant.now())
                .build());
    }
}
