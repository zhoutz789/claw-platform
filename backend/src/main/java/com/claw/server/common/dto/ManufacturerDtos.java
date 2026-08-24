package com.claw.server.common.dto;

import com.claw.server.common.enums.AssetLifecycleStage;
import com.claw.server.common.enums.AssetStatus;
import com.claw.server.common.enums.AssetType;
import com.claw.server.common.enums.VehicleOpType;
import com.claw.server.common.dto.ApiViews.AssetView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 厂家 / 商品 / SKU / 采购 / 资产全生命周期数据 出入参。 */
public final class ManufacturerDtos {

    private ManufacturerDtos() {
    }

    // ===== 视图 =====
    public record ManufacturerView(Long id, String code, String name, String contact, String country, String status) {
    }

    public record ProductView(Long id, Long manufacturerId, String name, AssetType assetType,
                              String model, String description, String status) {
    }

    public record ProductSkuView(Long id, Long productId, String skuCode, BigDecimal price,
                                 String currency, String specsJson, String status) {
    }

    public record PurchaseOrderView(Long id, String orderNo, Long productId, Long skuId, Long buyerId,
                                    Integer qty, BigDecimal unitPrice, BigDecimal totalAmount, String currency,
                                    String status, Instant paidAt, Instant shippedAt) {
    }

    public record QrRegisterItem(String serialNumber, String qrCode, String assetNo, AssetType assetType) {
    }

    public record AssetBirthResult(int bornCount, List<Long> assetIds) {
    }

    // ===== 资产全生命周期数据视图 =====
    public record LifecycleEventView(Long id, Long assetId, AssetLifecycleStage stage, String location,
                                     Long operatorId, String note, Instant occurredAt) {
    }

    public record MaintenanceView(Long id, Long assetId, Instant servicedAt, String mtype, String vendor,
                                  BigDecimal cost, String note) {
    }

    public record UsageView(Long id, Long assetId, Instant periodStart, Instant periodEnd, BigDecimal mileageKm,
                            Integer cycles, BigDecimal energyKwh, String note) {
    }

    public record VehicleOpsView(Long id, Long assetId, VehicleOpType opType, Instant startedAt, Instant endedAt,
                                 BigDecimal revenue, String detailJson, String note) {
    }

    // ===== 请求 =====
    public record UpsertManufacturer(String code, String name, String contact, String country, String status) {
    }

    public record UpsertProduct(Long manufacturerId, String name, AssetType assetType, String model,
                                String description, String status) {
    }

    public record UpsertSku(Long productId, String skuCode, BigDecimal price, String currency,
                            String specsJson, String status) {
    }

    public record CreatePurchase(Long productId, Long skuId, Long buyerId, Integer qty,
                                 BigDecimal unitPrice, String currency) {
    }

    public record RegisterQr(Long orderId, List<QrRegisterItem> items) {
    }

    /** 资产溯源聚合视图。 */
    public record AssetTraceView(AssetView asset, AssetType assetType, AssetStatus status,
                                 List<LifecycleEventView> lifecycle, List<MaintenanceView> maintenance,
                                 List<UsageView> usage, List<VehicleOpsView> vehicleOps,
                                 BigDecimal totalRevenue) {
    }
}
