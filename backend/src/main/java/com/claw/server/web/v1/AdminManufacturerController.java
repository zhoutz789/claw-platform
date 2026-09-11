package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ApiViews.AssetView;
import com.claw.server.common.dto.ManufacturerDtos.*;
import com.claw.server.common.enums.AssetLifecycleStage;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.VehicleOpType;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.asset.*;
import com.claw.server.domain.manufacturer.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.claw.server.common.security.RequirePermission;

/**
 * 厂家 / 商品 / SKU / 采购 / 二维码登记 / 资产全生命周期数据（闭环核心）。
 *
 * <p>流程：厂家发布商品 → 建 SKU 定价 → 客户采购(PAID) → 发货(SHIPPED) →
 * 出厂前逐台登记序列号+二维码 → 每台登记生出一台 Asset（绑定 product/sku/serial/qr）+ 写生命周期 PRODUCED。
 *
 * <p>资产溯源：出厂数据 / 生命周期 / 维修 / 使用 / 车辆运营 / 收益，皆以序列号二维码识别。
 */
@RestController
@RequestMapping("/api/v1/admin/manufacturer")
@RequiredArgsConstructor
public class AdminManufacturerController {

    private final ManufacturerRepository manufacturerRepository;
    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final AssetRepository assetRepository;
    private final AssetLifecycleEventRepository lifecycleRepository;
    private final AssetMaintenanceRecordRepository maintenanceRepository;
    private final AssetUsageRecordRepository usageRepository;
    private final AssetVehicleOpsRepository vehicleOpsRepository;

    /* ===================== 厂家 ===================== */
    @GetMapping("/manufacturers")
    public ApiResult<List<ManufacturerView>> listManufacturers() {
        return ApiResult.ok(manufacturerRepository.findAll().stream()
                .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
                .map(m -> new ManufacturerView(m.getId(), m.getCode(), m.getName(), m.getContact(),
                        m.getCountry(), m.getStatus()))
                .toList());
    }

    @PostMapping("/manufacturers")
    @RequirePermission("manufacturer:create")
    public ApiResult<ManufacturerView> createManufacturer(@RequestBody UpsertManufacturer req) {
        if (manufacturerRepository.findByCode(req.code()).isPresent())
            throw new BizException(40901, "manufacturer.code.exists");
        Manufacturer m = Manufacturer.builder().code(req.code()).name(req.name()).contact(req.contact())
                .country(req.country()).status(req.status() == null ? "ACTIVE" : req.status()).build();
        m = manufacturerRepository.save(m);
        return ApiResult.ok(new ManufacturerView(m.getId(), m.getCode(), m.getName(), m.getContact(),
                m.getCountry(), m.getStatus()));
    }

    @PutMapping("/manufacturers/{id}")
    @RequirePermission("manufacturer:update")
    public ApiResult<ManufacturerView> updateManufacturer(@PathVariable Long id, @RequestBody UpsertManufacturer req) {
        Manufacturer m = manufacturerRepository.findById(id).orElseThrow(() -> new BizException(40401, "manufacturer.not.found"));
        if (req.name() != null) m.setName(req.name());
        if (req.contact() != null) m.setContact(req.contact());
        if (req.country() != null) m.setCountry(req.country());
        if (req.status() != null) m.setStatus(req.status());
        m.setUpdatedAt(Instant.now());
        m = manufacturerRepository.save(m);
        return ApiResult.ok(new ManufacturerView(m.getId(), m.getCode(), m.getName(), m.getContact(),
                m.getCountry(), m.getStatus()));
    }

    @DeleteMapping("/manufacturers/{id}")
    @RequirePermission("manufacturer:delete")
    public ApiResult<Void> deleteManufacturer(@PathVariable Long id) {
        Manufacturer m = manufacturerRepository.findById(id).orElseThrow(() -> new BizException(40401, "manufacturer.not.found"));
        m.setDeleted(true);
        m.setUpdatedAt(Instant.now());
        manufacturerRepository.save(m);
        return ApiResult.ok();
    }

    /* ===================== 商品 ===================== */
    @GetMapping("/products")
    public ApiResult<List<ProductView>> listProducts(@RequestParam(required = false) Long manufacturerId) {
        return ApiResult.ok(productRepository.findAll().stream()
                .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                .filter(p -> manufacturerId == null || p.getManufacturerId().equals(manufacturerId))
                .map(this::toProductView)
                .toList());
    }

    @PostMapping("/products")
    @RequirePermission("manufacturer:create")
    public ApiResult<ProductView> createProduct(@RequestBody UpsertProduct req) {
        if (!manufacturerRepository.existsById(req.manufacturerId()))
            throw new BizException(40401, "manufacturer.not.found");
        Product p = Product.builder().manufacturerId(req.manufacturerId()).name(req.name())
                .assetType(req.assetType()).model(req.model()).description(req.description())
                .status(req.status() == null ? "ON_SALE" : req.status())
                .brand(req.brand()).category(req.category()).paramsJson(req.paramsJson())
                .coverImagesJson(req.coverImagesJson()).detail(req.detail()).videoUrl(req.videoUrl())
                .liveEnabled(req.liveEnabled() != null && req.liveEnabled())
                .liveUrl(req.liveUrl())
                .rewardRate(req.rewardRate() != null ? req.rewardRate() : BigDecimal.ZERO)
                .build();
        p = productRepository.save(p);
        // 生成唯一分享码（需先落库拿到自增 id）
        if (p.getShareCode() == null) {
            p.setShareCode("P" + p.getId());
            p = productRepository.save(p);
        }
        return ApiResult.ok(toProductView(p));
    }

    @PutMapping("/products/{id}")
    @RequirePermission("manufacturer:update")
    public ApiResult<ProductView> updateProduct(@PathVariable Long id, @RequestBody UpsertProduct req) {
        Product p = productRepository.findById(id).orElseThrow(() -> new BizException(40401, "product.not.found"));
        if (req.manufacturerId() != null) p.setManufacturerId(req.manufacturerId());
        if (req.name() != null) p.setName(req.name());
        if (req.assetType() != null) p.setAssetType(req.assetType());
        if (req.model() != null) p.setModel(req.model());
        if (req.description() != null) p.setDescription(req.description());
        if (req.status() != null) p.setStatus(req.status());
        if (req.brand() != null) p.setBrand(req.brand());
        if (req.category() != null) p.setCategory(req.category());
        if (req.paramsJson() != null) p.setParamsJson(req.paramsJson());
        if (req.coverImagesJson() != null) p.setCoverImagesJson(req.coverImagesJson());
        if (req.detail() != null) p.setDetail(req.detail());
        if (req.videoUrl() != null) p.setVideoUrl(req.videoUrl());
        if (req.liveEnabled() != null) p.setLiveEnabled(req.liveEnabled());
        if (req.liveUrl() != null) p.setLiveUrl(req.liveUrl());
        if (req.rewardRate() != null) p.setRewardRate(req.rewardRate());
        p.setUpdatedAt(Instant.now());
        p = productRepository.save(p);
        return ApiResult.ok(toProductView(p));
    }

    @GetMapping("/products/{id}")
    public ApiResult<ProductView> getProduct(@PathVariable Long id) {
        Product p = productRepository.findById(id).orElseThrow(() -> new BizException(40401, "product.not.found"));
        return ApiResult.ok(toProductView(p));
    }

    @PostMapping("/products/{id}/share")
    @RequirePermission("manufacturer:create")
    public ApiResult<Map<String, String>> createShare(@PathVariable Long id) {
        Product p = productRepository.findById(id).orElseThrow(() -> new BizException(40401, "product.not.found"));
        if (p.getShareCode() == null) {
            p.setShareCode("P" + p.getId());
            p.setUpdatedAt(Instant.now());
            p = productRepository.save(p);
        }
        String code = p.getShareCode();
        return ApiResult.ok(Map.of("shareCode", code, "shareUrl", "https://claw.app/g/" + code));
    }

    @DeleteMapping("/products/{id}")
    @RequirePermission("manufacturer:delete")
    public ApiResult<Void> deleteProduct(@PathVariable Long id) {
        Product p = productRepository.findById(id).orElseThrow(() -> new BizException(40401, "product.not.found"));
        p.setDeleted(true);
        p.setUpdatedAt(Instant.now());
        productRepository.save(p);
        return ApiResult.ok();
    }

    /* ===================== SKU ===================== */
    @GetMapping("/skus")
    public ApiResult<List<ProductSkuView>> listSkus(@RequestParam(required = false) Long productId) {
        return ApiResult.ok(productSkuRepository.findAll().stream()
                .filter(s -> !Boolean.TRUE.equals(s.getDeleted()))
                .filter(s -> productId == null || s.getProductId().equals(productId))
                .map(s -> new ProductSkuView(s.getId(), s.getProductId(), s.getSkuCode(), s.getPrice(),
                        s.getCurrency(), s.getSpecsJson(), s.getStatus()))
                .toList());
    }

    @PostMapping("/skus")
    @RequirePermission("manufacturer:create")
    public ApiResult<ProductSkuView> createSku(@RequestBody UpsertSku req) {
        if (!productRepository.existsById(req.productId()))
            throw new BizException(40401, "product.not.found");
        if (productSkuRepository.findBySkuCode(req.skuCode()).isPresent())
            throw new BizException(40901, "sku.code.exists");
        ProductSku s = ProductSku.builder().productId(req.productId()).skuCode(req.skuCode())
                .price(req.price() == null ? BigDecimal.ZERO : req.price())
                .currency(req.currency() == null ? "USD" : req.currency())
                .specsJson(req.specsJson()).status(req.status() == null ? "ACTIVE" : req.status()).build();
        s = productSkuRepository.save(s);
        return ApiResult.ok(new ProductSkuView(s.getId(), s.getProductId(), s.getSkuCode(), s.getPrice(),
                s.getCurrency(), s.getSpecsJson(), s.getStatus()));
    }

    @PutMapping("/skus/{id}")
    @RequirePermission("manufacturer:update")
    public ApiResult<ProductSkuView> updateSku(@PathVariable Long id, @RequestBody UpsertSku req) {
        ProductSku s = productSkuRepository.findById(id).orElseThrow(() -> new BizException(40401, "sku.not.found"));
        if (req.productId() != null) s.setProductId(req.productId());
        if (req.price() != null) s.setPrice(req.price());
        if (req.currency() != null) s.setCurrency(req.currency());
        if (req.specsJson() != null) s.setSpecsJson(req.specsJson());
        if (req.status() != null) s.setStatus(req.status());
        s.setUpdatedAt(Instant.now());
        s = productSkuRepository.save(s);
        return ApiResult.ok(new ProductSkuView(s.getId(), s.getProductId(), s.getSkuCode(), s.getPrice(),
                s.getCurrency(), s.getSpecsJson(), s.getStatus()));
    }

    @DeleteMapping("/skus/{id}")
    @RequirePermission("manufacturer:delete")
    public ApiResult<Void> deleteSku(@PathVariable Long id) {
        ProductSku s = productSkuRepository.findById(id).orElseThrow(() -> new BizException(40401, "sku.not.found"));
        s.setDeleted(true);
        s.setUpdatedAt(Instant.now());
        productSkuRepository.save(s);
        return ApiResult.ok();
    }

    /* ===================== 采购订单 ===================== */
    @GetMapping("/purchase-orders")
    public ApiResult<List<PurchaseOrderView>> listOrders() {
        return ApiResult.ok(purchaseOrderRepository.findAll().stream()
                .filter(o -> !Boolean.TRUE.equals(o.getDeleted()))
                .map(this::toOrderView).toList());
    }

    @PostMapping("/purchase-orders")
    @RequirePermission("manufacturer:create")
    public ApiResult<PurchaseOrderView> createOrder(@RequestBody CreatePurchase req) {
        Product product = productRepository.findById(req.productId())
                .orElseThrow(() -> new BizException(40401, "product.not.found"));
        ProductSku sku = productSkuRepository.findById(req.skuId())
                .orElseThrow(() -> new BizException(40401, "sku.not.found"));
        int qty = req.qty() == null || req.qty() < 1 ? 1 : req.qty();
        BigDecimal unit = req.unitPrice() != null ? req.unitPrice() : sku.getPrice();
        PurchaseOrder o = PurchaseOrder.builder().orderNo("PO" + System.currentTimeMillis())
                .productId(req.productId()).skuId(req.skuId()).buyerId(req.buyerId()).qty(qty)
                .unitPrice(unit).totalAmount(unit.multiply(BigDecimal.valueOf(qty)))
                .currency(req.currency() == null ? sku.getCurrency() : req.currency())
                .status("CREATED").build();
        o = purchaseOrderRepository.save(o);
        return ApiResult.ok(toOrderView(o));
    }

    @PutMapping("/purchase-orders/{id}/pay")
    @RequirePermission("manufacturer:update")
    public ApiResult<PurchaseOrderView> payOrder(@PathVariable Long id) {
        PurchaseOrder o = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "purchase.order.not.found"));
        o.setStatus("PAID");
        o.setPaidAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        o = purchaseOrderRepository.save(o);
        return ApiResult.ok(toOrderView(o));
    }

    @PutMapping("/purchase-orders/{id}/ship")
    @RequirePermission("manufacturer:update")
    public ApiResult<PurchaseOrderView> shipOrder(@PathVariable Long id) {
        PurchaseOrder o = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "purchase.order.not.found"));
        if (!"PAID".equals(o.getStatus())) throw new BizException(40902, "order.must.be.paid");
        o.setStatus("SHIPPED");
        o.setShippedAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        o = purchaseOrderRepository.save(o);
        return ApiResult.ok(toOrderView(o));
    }

    /** 出厂前逐台登记序列号+二维码 → 生出 Asset + 生命周期 PRODUCED。 */
    @Transactional
    @PostMapping("/purchase-orders/{id}/register-qr")
    @RequirePermission("manufacturer:create")
    public ApiResult<AssetBirthResult> registerQr(@PathVariable Long id, @RequestBody RegisterQr req) {
        PurchaseOrder o = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new BizException(40401, "purchase.order.not.found"));
        Product product = productRepository.findById(o.getProductId()).orElseThrow();
        Long uid = AuthContext.currentUserId();
        List<Long> born = new java.util.ArrayList<>();
        for (QrRegisterItem it : req.items()) {
            String assetNo = it.assetNo() != null ? it.assetNo() : ("A" + System.currentTimeMillis() + born.size());
            // 二维码：空白视为未填，自动按「CLAW|ASSET|<资产编号>|<序列号>」生成，保证唯一且可扫码溯源
            // （qr_code 列唯一，空字符串 "" 会触发唯一约束冲突，故必须归一为 null 或生成值）
            String qr = (it.qrCode() != null && !it.qrCode().isBlank())
                    ? it.qrCode().trim()
                    : ("CLAW|ASSET|" + assetNo + "|" + (it.serialNumber() == null ? "" : it.serialNumber()));
            Asset asset = Asset.builder()
                    .assetType(it.assetType() != null ? it.assetType() : product.getAssetType())
                    .assetNo(assetNo).qrCode(qr).serialNumber(it.serialNumber())
                    .manufacturerId(product.getManufacturerId()).productId(o.getProductId()).skuId(o.getSkuId())
                    .ownerId(o.getBuyerId()).status(AssetStatus.IN_STOCK).tenantId(1L).build();
            asset = assetRepository.save(asset);
            lifecycleRepository.save(AssetLifecycleEvent.builder()
                    .assetId(asset.getId()).stage(AssetLifecycleStage.PRODUCED)
                    .operatorId(uid).note("出厂登记·来自采购单" + o.getOrderNo())
                    .occurredAt(Instant.now()).build());
            born.add(asset.getId());
        }
        if ("CREATED".equals(o.getStatus()) || "PAID".equals(o.getStatus())) {
            o.setStatus("SHIPPED");
            o.setShippedAt(Instant.now());
            o.setUpdatedAt(Instant.now());
            purchaseOrderRepository.save(o);
        }
        return ApiResult.ok(new AssetBirthResult(born.size(), born));
    }

    private PurchaseOrderView toOrderView(PurchaseOrder o) {
        return new PurchaseOrderView(o.getId(), o.getOrderNo(), o.getProductId(), o.getSkuId(), o.getBuyerId(),
                o.getQty(), o.getUnitPrice(), o.getTotalAmount(), o.getCurrency(), o.getStatus(),
                o.getPaidAt(), o.getShippedAt());
    }

    private ProductView toProductView(Product p) {
        return new ProductView(p.getId(), p.getManufacturerId(), p.getName(), p.getAssetType(),
                p.getModel(), p.getDescription(), p.getStatus(),
                p.getBrand(), p.getCategory(), p.getParamsJson(), p.getCoverImagesJson(),
                p.getDetail(), p.getVideoUrl(), p.isLiveEnabled(), p.getLiveUrl(),
                p.getShareCode(), p.getRewardRate());
    }

    /* ===================== 资产溯源 + 全生命周期数据 ===================== */
    @GetMapping("/assets/{id}/trace")
    public ApiResult<AssetTraceView> trace(@PathVariable Long id) {
        Asset asset = assetRepository.findById(id).orElseThrow(() -> new BizException(40401, "error.asset.not.found"));
        AssetView av = new AssetView(asset.getId(), asset.getAssetType(), asset.getAssetNo(), asset.getQrCode(),
                asset.getSerialNumber(), asset.getManufacturerId(), asset.getProductId(), asset.getSkuId(),
                asset.getOwnerId(), asset.getUserId(), asset.getStatus(), asset.getCreatedAt());
        List<LifecycleEventView> lifecycle = lifecycleRepository.findByAssetIdOrderByOccurredAtDesc(id).stream()
                .map(e -> new LifecycleEventView(e.getId(), e.getAssetId(), e.getStage(), e.getLocation(),
                        e.getOperatorId(), e.getNote(), e.getOccurredAt())).toList();
        List<MaintenanceView> maintenance = maintenanceRepository.findByAssetIdOrderByServicedAtDesc(id).stream()
                .map(m -> new MaintenanceView(m.getId(), m.getAssetId(), m.getServicedAt(), m.getMtype(), m.getVendor(),
                        m.getCost(), m.getNote())).toList();
        List<UsageView> usage = usageRepository.findByAssetIdOrderByPeriodStartDesc(id).stream()
                .map(u -> new UsageView(u.getId(), u.getAssetId(), u.getPeriodStart(), u.getPeriodEnd(), u.getMileageKm(),
                        u.getCycles(), u.getEnergyKwh(), u.getNote())).toList();
        List<VehicleOpsView> vops = vehicleOpsRepository.findByAssetIdOrderByStartedAtDesc(id).stream()
                .map(v -> new VehicleOpsView(v.getId(), v.getAssetId(), v.getOpType(), v.getStartedAt(), v.getEndedAt(),
                        v.getRevenue(), v.getDetailJson(), v.getNote())).toList();
        BigDecimal revenue = vops.stream().map(VehicleOpsView::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        return ApiResult.ok(new AssetTraceView(av, asset.getAssetType(), asset.getStatus(),
                lifecycle, maintenance, usage, vops, revenue));
    }

    @PostMapping("/assets/{id}/lifecycle")
    @RequirePermission("manufacturer:create")
    public ApiResult<LifecycleEventView> addLifecycle(@PathVariable Long id, @RequestBody LifecycleReq req) {
        if (!assetRepository.existsById(id)) throw new BizException(40401, "error.asset.not.found");
        AssetLifecycleEvent e = lifecycleRepository.save(AssetLifecycleEvent.builder()
                .assetId(id).stage(req.stage()).location(req.location()).operatorId(AuthContext.currentUserId())
                .note(req.note()).occurredAt(req.occurredAt() == null ? Instant.now() : req.occurredAt()).build());
        return ApiResult.ok(new LifecycleEventView(e.getId(), e.getAssetId(), e.getStage(), e.getLocation(),
                e.getOperatorId(), e.getNote(), e.getOccurredAt()));
    }

    @PostMapping("/assets/{id}/maintenance")
    @RequirePermission("manufacturer:create")
    public ApiResult<MaintenanceView> addMaintenance(@PathVariable Long id, @RequestBody MaintenanceReq req) {
        if (!assetRepository.existsById(id)) throw new BizException(40401, "error.asset.not.found");
        AssetMaintenanceRecord m = maintenanceRepository.save(AssetMaintenanceRecord.builder()
                .assetId(id).servicedAt(req.servicedAt() == null ? Instant.now() : req.servicedAt())
                .mtype(req.mtype()).vendor(req.vendor()).cost(req.cost()).note(req.note()).build());
        return ApiResult.ok(new MaintenanceView(m.getId(), m.getAssetId(), m.getServicedAt(), m.getMtype(),
                m.getVendor(), m.getCost(), m.getNote()));
    }

    @PostMapping("/assets/{id}/usage")
    @RequirePermission("manufacturer:create")
    public ApiResult<UsageView> addUsage(@PathVariable Long id, @RequestBody UsageReq req) {
        if (!assetRepository.existsById(id)) throw new BizException(40401, "error.asset.not.found");
        AssetUsageRecord u = usageRepository.save(AssetUsageRecord.builder()
                .assetId(id).periodStart(req.periodStart()).periodEnd(req.periodEnd())
                .mileageKm(req.mileageKm() == null ? BigDecimal.ZERO : req.mileageKm())
                .cycles(req.cycles() == null ? 0 : req.cycles())
                .energyKwh(req.energyKwh() == null ? BigDecimal.ZERO : req.energyKwh()).note(req.note()).build());
        return ApiResult.ok(new UsageView(u.getId(), u.getAssetId(), u.getPeriodStart(), u.getPeriodEnd(),
                u.getMileageKm(), u.getCycles(), u.getEnergyKwh(), u.getNote()));
    }

    @PostMapping("/assets/{id}/vehicle-ops")
    @RequirePermission("manufacturer:create")
    public ApiResult<VehicleOpsView> addVehicleOps(@PathVariable Long id, @RequestBody VehicleOpsReq req) {
        if (!assetRepository.existsById(id)) throw new BizException(40401, "error.asset.not.found");
        AssetVehicleOps v = vehicleOpsRepository.save(AssetVehicleOps.builder()
                .assetId(id).opType(req.opType()).startedAt(req.startedAt() == null ? Instant.now() : req.startedAt())
                .endedAt(req.endedAt()).revenue(req.revenue() == null ? BigDecimal.ZERO : req.revenue())
                .detailJson(req.detailJson()).note(req.note()).build());
        return ApiResult.ok(new VehicleOpsView(v.getId(), v.getAssetId(), v.getOpType(), v.getStartedAt(),
                v.getEndedAt(), v.getRevenue(), v.getDetailJson(), v.getNote()));
    }

    /* ===================== 请求体 ===================== */
    public record LifecycleReq(AssetLifecycleStage stage, String location, String note, Instant occurredAt) {
    }

    public record MaintenanceReq(Instant servicedAt, String mtype, String vendor, BigDecimal cost, String note) {
    }

    public record UsageReq(Instant periodStart, Instant periodEnd, BigDecimal mileageKm, Integer cycles,
                           BigDecimal energyKwh, String note) {
    }

    public record VehicleOpsReq(VehicleOpType opType, Instant startedAt, Instant endedAt, BigDecimal revenue,
                                String detailJson, String note) {
    }
}
