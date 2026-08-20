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
}
