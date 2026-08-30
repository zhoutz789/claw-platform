package com.claw.server.domain.recovery;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.CustodyStatus;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.common.enums.RecoveryStatus;
import com.claw.server.domain.consignment.ConsignmentCustody;
import com.claw.server.domain.consignment.ConsignmentCustodyRepository;
import com.claw.server.domain.inventory.Inventory;
import com.claw.server.domain.inventory.InventoryRepository;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.settings.SystemConfig;
import com.claw.server.domain.settings.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 设备回收服务（增量 B · R8）。
 *
 * <p>回收单与车辆残值回收 recovery_orders 解耦（新表 device_recovery_orders），引用 assets(id)。
 * 触发：UNSOLD_TIMEOUT（寄售未成交超时，从入寄售库 inbound_at 起算，默认 90 天可配）/ FULFILL_TIMEOUT / MANUAL。
 * 定时扫描（每日 03:00）对超时在站寄售设备自动建回收单（AUTO）。确认回收后资产回流厂家自有库。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecoveryService {

    private final DeviceRecoveryOrderRepository recoveryRepository;
    private final ConsignmentCustodyRepository custodyRepository;
    private final InventoryRepository inventoryRepository;
    private final DeviceRepository deviceRepository;
    private final SystemConfigRepository systemConfigRepository;

    @Transactional
    public DeviceRecoveryOrder createRecovery(Long manufacturerId, Long stationId, Long assetId,
                                             String reason, String triggerType, Long operatorId) {
        DeviceRecoveryOrder o = DeviceRecoveryOrder.builder()
                .recoveryNo("RC" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 16))
                .manufacturerId(manufacturerId)
                .stationId(stationId)
                .assetId(assetId)
                .reason(reason)
                .triggerType(triggerType == null ? "MANUAL" : triggerType)
                .status(RecoveryStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        return recoveryRepository.save(o);
    }

    /** 确认回收：资产回流厂家自有库（库存转 OWNED_BY_MFG，占有权结束）。 */
    @Transactional
    public DeviceRecoveryOrder confirmRecovery(Long recoveryId, Long operatorId) {
        DeviceRecoveryOrder o = recoveryRepository.findById(recoveryId)
                .orElseThrow(() -> BizException.of(40401, "recovery.order.not.found"));
        if (o.getStatus() != RecoveryStatus.PENDING && o.getStatus() != RecoveryStatus.CONFIRMED) {
            throw BizException.of(40920, "recovery.not.confirmable");
        }
        Device device = deviceRepository.findByAssetId(o.getAssetId()).stream().findFirst().orElse(null);
        if (device != null) {
            inventoryRepository.findByDeviceId(device.getId()).ifPresent(inv -> {
                inv.setOwnershipType(OwnershipType.OWNED_BY_MFG);
                inv.setHolderStationId(null);
                inv.setCurrentStatus(LifecycleStatus.RECALLED);
                inv.setInboundAt(Instant.now());
                inv.setUpdatedAt(Instant.now());
                inventoryRepository.save(inv);
            });
            device.setLifecycleStatus(LifecycleStatus.RECALLED.name());
            deviceRepository.save(device);
            custodyRepository.findByDeviceId(device.getId()).ifPresent(c -> {
                c.setStatus(CustodyStatus.RETURNED);
                c.setEndedAt(Instant.now());
                c.setEndedReason("RECOVERED");
                custodyRepository.save(c);
            });
        }
        o.setStatus(RecoveryStatus.CONFIRMED);
        o.setConfirmedAt(Instant.now());
        o.setInboundAt(Instant.now());
        return recoveryRepository.save(o);
    }

    @Transactional(readOnly = true)
    public List<DeviceRecoveryOrder> listRecoveries(Long manufacturerId) {
        return (manufacturerId == null)
                ? recoveryRepository.findAll()
                : recoveryRepository.findByManufacturerId(manufacturerId);
    }

    @Transactional(readOnly = true)
    public DeviceRecoveryOrder getRecovery(Long id) {
        return recoveryRepository.findById(id)
                .orElseThrow(() -> BizException.of(40401, "recovery.order.not.found"));
    }

    /** 定时扫描：寄售未成交超时（从入寄售库 inbound_at 起算 RECOVERY_DAYS，默认 90）自动建回收单。 */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void scanUnsoldRecoveries() {
        int days = readConfigInt("RECOVERY_DAYS", 90);
        Instant threshold = Instant.now().minus(Duration.ofDays(days));
        Set<Long> pendingAssets = recoveryRepository.findByStatus(RecoveryStatus.PENDING).stream()
                .map(DeviceRecoveryOrder::getAssetId)
                .collect(Collectors.toCollection(HashSet::new));
        List<Inventory> atStation = inventoryRepository.findByCurrentStatus(LifecycleStatus.AT_STATION);
        int created = 0;
        for (Inventory inv : atStation) {
            if (inv.getInboundAt() == null || !inv.getInboundAt().isBefore(threshold)) {
                continue;
            }
            Device d = deviceRepository.findById(inv.getDeviceId()).orElse(null);
            if (d == null || d.getAssetId() == null) {
                continue;
            }
            if (pendingAssets.contains(d.getAssetId())) {
                continue;
            }
            createRecovery(inv.getOwnerManufacturerId(), inv.getHolderStationId(), d.getAssetId(),
                    "UNSOLD_TIMEOUT", "AUTO", null);
            pendingAssets.add(d.getAssetId());
            created++;
        }
        if (created > 0) {
            log.info("回收扫描：自动创建 {} 张寄售超时回收单（RECOVERY_DAYS={}）", created, days);
        }
    }

    private int readConfigInt(String key, int fallback) {
        SystemConfig cfg = systemConfigRepository.findByConfigKeyAndDeletedFalse(key).orElse(null);
        if (cfg == null || cfg.getConfigValue() == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(cfg.getConfigValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
