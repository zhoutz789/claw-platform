package com.claw.server.domain.production;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews;
import com.claw.server.common.dto.AssetRequests.ProvisionAssetReq;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.LifecycleStatus;
import com.claw.server.common.enums.OwnershipType;
import com.claw.server.domain.asset.AssetService;
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
import java.util.UUID;

/**
 * 生产任务服务（增量 B · R3/B1）。
 *
 * <p>基于 product 实例化 device 的批次：CREATE → PRODUCING → DONE。
 * 完工时每台设备：① 经 {@link AssetService} 建档拿到 assetId ② 建 devices 行并回填 asset_id
 * ③ 生成合格证（Q8）④ 入厂家库存台账 ⑤ 写流通链事件 PRODUCE。
 *
 * <p><b>为什么必须先建档</b>：{@code claw.devices.asset_id} 是 {@code NOT NULL REFERENCES claw.assets(id)}，
 * 直接建 devices 行会撞非空约束，生产入库在第①步就失败。资产建档不在此处另起炉灶，而是复用资产域
 * 既有入口 {@link AssetService#provisionFromRegistration}（建档 → PRODUCED 生命周期 → 授产权），
 * 与订单「逐台登记」{@code UnitRegistrationService} 走同一条链路，保证归属/产权语义一致。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductionService {

    private final ProductionTaskRepository taskRepository;
    private final ProductRepository productRepository;
    private final ManufacturerRepository manufacturerRepository;
    private final AssetService assetService;
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
        AssetType assetType = product.getAssetType();
        if (assetType == null) {
            // 无资产类型无法建档：AssetService 建档按类型分派扩展表，devices.asset_id 亦非空。
            throw BizException.invalidParam("production.product.asset.type.missing");
        }
        int n = (producedQuantity == null || producedQuantity <= 0) ? t.getPlanQuantity() : producedQuantity;
        t.setProducedQuantity(n);
        t.setStatus("DONE");
        t.setUpdatedAt(Instant.now());
        t = taskRepository.save(t);
        String deviceType = deriveDeviceType(assetType);
        for (int i = 0; i < n; i++) {
            int seq = i + 1;
            String serialNumber = generateSerialNumber(product.getId(), t.getId(), seq);
            String qrCode = generateQrCode(t.getId(), seq);

            // ① 建档：复用资产域既有入口（建档 → PRODUCED → 授产权），拿到 assetId。
            //    ownerId 传操作人（厂家侧建档人），与 AssetService 建档「未指定 ownerId 即归属操作人」语义一致；
            //    厂家归属由 manufacturerId 溯源，orderItemId 为空（生产非订单登记）。
            ApiViews.AssetView asset = assetService.provisionFromRegistration(
                    new ProvisionAssetReq(
                            assetType.name(),
                            null,                 // assetNo：缺省由 AssetService 自动生成
                            qrCode,
                            product.getId(),
                            null,                 // skuId：生产入库无 SKU 维度
                            t.getManufacturerId(),
                            serialNumber,
                            operatorId,
                            null,                 // orderItemId
                            null, null, null,     // vin / frameNo / motorNo（出厂未指定）
                            null,                 // remoteId：无人机缺省由 AssetService 自动生成
                            product.getModel(),
                            null,                 // capacityKwh
                            null),                // componentNosJson
                    operatorId);

            // ② 建 devices 行并回填 asset_id（NOT NULL 约束要求）
            Device d = deviceRepository.save(Device.builder()
                    .assetId(asset.id())
                    .productId(product.getId())
                    .deviceType(deviceType)
                    .lifecycleStatus(LifecycleStatus.PRODUCING.name())
                    .status("ACTIVE")
                    .build());

            // ③ 生成合格证（Q8：出厂即写库，不可补证）
            certificateService.issue(d.getId(), t.getManufacturerId(), operatorId, product);

            // ④ 入厂家库存台账（OWNED_BY_MFG / PRODUCING）
            inventoryRepository.save(Inventory.builder()
                    .assetId(asset.id())
                    .deviceId(d.getId())
                    .ownershipType(OwnershipType.OWNED_BY_MFG)
                    .ownerManufacturerId(t.getManufacturerId())
                    .productId(product.getId())
                    .serialNumber(serialNumber)
                    .currentStatus(LifecycleStatus.PRODUCING)
                    .inboundAt(Instant.now())
                    .build());

            // ⑤ 流通链事件 PRODUCE
            lifecycleRepository.save(LifecycleEvent.builder()
                    .deviceId(d.getId())
                    .toStatus(LifecycleStatus.PRODUCING.name())
                    .eventType("PRODUCE")
                    .operatorId(operatorId)
                    .occurredAt(Instant.now())
                    .build());
        }
        log.info("生产任务 {} 完成，实例化设备 {} 台（产品 {} / 资产类型 {}）",
                taskId, n, product.getId(), assetType);
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

    /**
     * 出厂序列号（写入 assets.serial_number / inventory.serial_number）。
     * 带随机后缀：assets.serial_number 上有 UNIQUE 索引，任务重复完工或跨任务同序号都不能撞号。
     */
    private String generateSerialNumber(Long productId, Long taskId, int seq) {
        return String.format("SN-%s-%s-%03d-%s", productId, taskId, seq, shortUuid());
    }

    /** 出厂二维码内容（assets.qr_code 唯一）。 */
    private String generateQrCode(Long taskId, int seq) {
        return String.format("QR-%s-%03d-%s", taskId, seq, shortUuid());
    }

    private String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 8);
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
