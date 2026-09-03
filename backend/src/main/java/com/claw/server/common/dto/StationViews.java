package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 站点域视图（station 出参）。 */
public final class StationViews {

    private StationViews() {
    }

    public static record StationView(
            Long id, String code, String name, String area,
            String province, String city, String district,
            String countryCode, String openHours,
            BigDecimal distKm,               // 距客户（Haversine 近似；未传坐标时为 null）
            int totalStock,                  // 现货总台数
            List<String> categories) {       // 现货品类摘要（如 比亚迪C1、雅迪T2）
    }

    public static record StockView(
            Long id, Long stationId, String skuCode, Integer stockQty, Instant updatedAt) {
    }

    public static record SkuHitView(
            StationView station, String skuCode, int availableQty) {
    }

    /** 地图适配层视图：附近换电站 + 电池供给（满电可换/充电中）。 */
    public record MapView(
            StationView station,
            long readyBatteries,     // 满电电池数（可立即换电）
            long chargingBatteries) {  // 充电中电池数
    }

    /** 服务站电池位视图（S4）。 */
    public record SlotView(
            Long id, Long stationId, Integer slotNo,
            Long batteryId, String batteryNo,
            String status, BigDecimal soc) {
    }

    /** 服务站扫码收发单视图（S4）。 */
    public record HandoverView(
            String handoverNo, Long stationId, Long batteryId, String batteryNo,
            String opType, String orderNo, Long operatorId, BigDecimal soc, Instant createdAt) {
    }

    /** 服务站日账单视图（S4）：当日换电营收 + 分账三拆 + 收发电次数。 */
    public record DailyBillView(
            Long stationId, String date,
            int swapCount, BigDecimal totalKwh,
            BigDecimal elecFee, BigDecimal serviceFee, BigDecimal totalRevenue,
            BigDecimal fundShare, BigDecimal stationShare, BigDecimal platformShare,
            int outCount, int inCount) {
    }

    /* ----------------------- 模块四 · 服务站三层解耦视图 ----------------------- */

    /** 库存层：当前库存行（复用 station_stock）。 */
    public record StationInventoryStockView(
            Long id, Long stationId, String skuCode, Integer stockQty, Instant updatedAt) {
    }

    /** 库存层：出入库流水。 */
    public record StationInventoryMovementView(
            Long id, Long stationId, String skuCode, Integer deltaQty, String reason,
            Long stationProjectId, String refType, Long refId, Long operatorId, Instant createdAt) {
    }

    /** 库存层：作用域视图（复用 InventoryScope，返回 allowedStationIds + 引导）。 */
    public record StationScopeView(
            Long overrideStationId, String level, List<Long> allowedStationIds) {
    }

    /** 项目层：项目视图。 */
    public record StationProjectView(
            Long id, Long stationId, Long ownerUserId, String name, Long parentId,
            Integer depth, Integer sortNo, String status, Instant createdAt, Instant updatedAt) {
    }

    /** 项目层：项目树节点（自引用）。 */
    public record StationProjectTreeNode(
            Long id, Long stationId, Long ownerUserId, String name, Long parentId,
            Integer depth, Integer sortNo, String status, Instant createdAt, Instant updatedAt,
            List<StationProjectTreeNode> children) {
    }

    /** 项目层：占用库存行（含读时计算的可用量）。 */
    public record StationProjectAllocView(
            Long id, Long stationProjectId, Long stationStockId, String skuCode,
            Integer allocatedQty, String note, Integer availableQty, Instant createdAt, Instant updatedAt) {
    }

    /** 结算层：结算单视图。 */
    public record StationSettlementView(
            Long id, String settlementNo, Long stationId, Instant periodStart, Instant periodEnd,
            String status, BigDecimal logisticsFee, BigDecimal stationCommission, BigDecimal manufacturerNet,
            String currency, Long createdBy, Instant createdAt, Instant confirmedAt, Instant paidAt) {
    }

    /** 结算层：结算明细视图。 */
    public record StationSettlementItemView(
            Long id, Long settlementId, String itemType, Long refId, String description,
            BigDecimal amount, String direction, Instant createdAt) {
    }

    /** 结算层：结算单 + 明细。 */
    public record StationSettlementDetailView(
            StationSettlementView settlement, List<StationSettlementItemView> items) {
    }
}
