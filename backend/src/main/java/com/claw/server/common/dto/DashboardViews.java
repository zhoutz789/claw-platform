package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 报表/大屏视图（report 出参）。所有指标均绑定真实库表，并标注数据来源口径。 */
public final class DashboardViews {

    private DashboardViews() {
    }

    /** 数据大屏聚合。每个指标在 {@code sources} 中标注来源表与口径。 */
    public record DashboardView(
            long totalSwapOrders,
            long readyBatteries,
            long chargingBatteries,
            List<EscrowBalanceView> escrowAccounts,
            // —— 新增：资产生命周期闭环相关 ——
            long manufacturerCount,
            long productCount,
            long skuCount,
            long assetCount,
            Map<String, Long> assetByStage,     // 各生命周期阶段资产数
            BigDecimal purchaseTotal,           // 累计采购额
            List<MetricSourceView> sources) {   // 指标来源口径
    }

    public record EscrowBalanceView(String escrowType, BigDecimal balance) {
    }

    /** 指标来源标注（让统计“说清楚从哪来”）。 */
    public record MetricSourceView(String key, String label, String sourceTable, String note) {
    }
}
