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
}
