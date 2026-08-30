package com.claw.server.domain.production;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.domain.certificate.CertificateService;
import com.claw.server.domain.inventory.Inventory;
import com.claw.server.domain.inventory.InventoryRepository;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.lifecycle.LifecycleEvent;
import com.claw.server.domain.lifecycle.LifecycleEventRepository;
import com.claw.server.domain.manufacturer.ManufacturerRepository;
import com.claw.server.domain.manufacturer.Product;
import com.claw.server.domain.manufacturer.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 生产任务服务（增量 B · R3/B1）。
 *
 * <p>基于 product 实例化 device 的批次：CREATE → PRODUCING → DONE。
 * 完工时每台设备：① 建 devices 行（product_id + lifecycle_status=PRODUCING）② 建库存台账（OWNED_BY_MFG/IN_FACTORY）
 * ③ 生成合格证（Q8）④ 写流通链事件 PRODUCE。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductionService {

    private final ProductionTaskRepository taskRepository;
    private final ProductRepository productRepository;
    private final ManufacturerRepository manufacturerRepository;
    private final DeviceRepository deviceRepository;
    private final InventoryRepository inventoryRepository;
    private final LifecycleEventRepository lifecycleRepository;
    private final CertificateService certificateService;

    @Transactional
    public ProductionTask createTask(Long manufacturerId, Long productId, int planQuantity, String specJson, Long operatorId) {
        if (manufacturerRepository.findById(manufacturerId).isEmpty()) {
            throw BizException.of(40401, "manufacturer.not.found");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> BizException.of(40401, "product.not.found"));
        ProductionTask t = ProductionTask.builder()
                .manufacturerId(manufacturerId)
                .productId(productId)
                .planQuantity(planQuantity <= 0 ? 0 : planQuantity)
                .producedQuantity(0)
                .status("CREATED")
                .specJson(specJson)
                .createdBy(operatorId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return taskRepository.save(t);
    }

    @Transactional
    public ProductionTask completeTask(Long taskId, Integer producedQuantity, Long operatorId) {
        ProductionTask t = taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.of(40401, "production.task.not.found"));
        Product product = productRepository.findById(t.getProductId())
                .orElseThrow(() -> BizException.of(40401, "product.not.found"));
        int n = (producedQuantity == null || producedQuantity <= 0) ? t.getPlanQuantity() : producedQuantity;
        t.setProducedQuantity(n);
        t.setStatus("DONE");
        t.setUpdatedAt(Instant.now());
        t = taskRepository.save(t);
        for (int i = 0; i < n; i++) {
            Device d = deviceRepository.save(Device.builder()
                    .productId(product.getId())
                    .deviceType(deriveDeviceType(product.getAssetType()))
                    .lifecycleStatus(LifecycleStatus.PRODUCING.name())
                    .status("ACTIVE")
                    .build());
            inventoryRepository.save(Inventory.builder()
                    .deviceId(d.getId())
                    .ownershipType(OwnershipType.OWNED_BY_MFG)
                    .ownerManufacturerId(t.getManufacturerId())
                    .currentStatus(LifecycleStatus.PRODUCING)
                    .inboundAt(Instant.now())
                    .build());
            certificateService.issue(d.getId(), t.getManufacturerId(), operatorId, product);
            lifecycleRepository.save(LifecycleEvent.builder()
                    .deviceId(d.getId())
                    .toStatus(LifecycleStatus.PRODUCING.name())
                    .eventType("PRODUCE")
                    .operatorId(operatorId)
                    .occurredAt(Instant.now())
                    .build());
        }
        log.info("生产任务 {} 完成，实例化设备 {} 台", taskId, n);
        return t;
    }

    @Transactional(readOnly = true)
    public List<ProductionTask> listTasks(Long manufacturerId) {
        return (manufacturerId == null)
                ? taskRepository.findAll()
                : taskRepository.findByManufacturerId(manufacturerId);
    }

    @Transactional(readOnly = true)
    public ProductionTask getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.of(40401, "production.task.not.found"));
    }

    /** 由资产类型推导设备终端类型（与 AssetService.deriveDeviceType 一致）。 */
    private String deriveDeviceType(AssetType type) {
        if (type == null) {
            return "DEVICE";
        }
        return switch (type) {
            case VEHICLE, EV -> "VEHICLE_TCU";
            case BATTERY -> "BATTERY_BMS";
            case CHARGER -> "CHARGER";
            case DRONE -> "DRONE_FCU";
            default -> "DEVICE";
        };
    }
}
