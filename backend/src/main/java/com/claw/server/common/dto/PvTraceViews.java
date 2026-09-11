package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 光伏追溯视图（只读查询出参，光伏追溯切片）。
 *
 * <p>所有"缺输入就算不出"的指标一律返回 {@code null}，绝不用 0 或猜测值填充
 * （0 会被误读为"实测量为零"，比 null 更危险）。各字段的缺失原因见对应注释。
 */
public final class PvTraceViews {

    private PvTraceViews() {
    }

    /**
     * 组件溯源视图。
     *
     * @param inverterEnergyWh        所在逆变器累计发电量 Wh（INVERTER 来源差分累计）；
     *                                组件未绑定逆变器时为 null
     * @param estimatedDegradationPct 估算累计衰减 %（年衰减率 × 已装年数）；缺 installedAt 或年衰减率时为 null
     * @param warrantyYears           质保年限；<b>恒为 null</b>——当前系统（含商品侧）无质保年限数据源，不编造
     */
    public record ModuleTraceView(
            String serialNo,
            Long stationAssetId,
            Long productId,
            Long skuId,
            Long manufacturerId,
            String batchNo,
            BigDecimal pmaxW,
            BigDecimal degradationRate,
            LocalDate installedAt,
            String stringId,
            Integer position,
            String inverterDeviceNo,
            Long certificateId,
            String elImageUrl,
            BigDecimal inverterEnergyWh,
            BigDecimal estimatedDegradationPct,
            Integer warrantyYears
    ) {
    }

    /**
     * 批次概览视图。
     *
     * @param pr 批次级性能比；<b>恒为 null</b>——同一批次组件分散在多个电站，
     *           无统一铭牌装机容量作分母，无法成立，不编造
     */
    public record BatchOverviewView(
            String batchNo,
            int moduleCount,
            List<ModuleTraceView> modules,
            BigDecimal pr
    ) {
    }

    /**
     * 电量桶（按天或按月）。
     *
     * @param inverterWh    INVERTER 来源电量 Wh
     * @param meterWh       METER 来源电量 Wh（与 inverterWh 分列，永不合并）
     * @param peakSunHours  峰值日照时数 h（= 该桶内各小时辐照度之和 ÷ 1000）
     * @param pr            性能比；无铭牌容量或缺辐照度时为 null
     * @param irradianceGap 该桶内存在辐照度缺失的小时（这些小时已从 PR 分子分母中剔除，
     *                      未被当作 0 拉低 PR）
     */
    public record YieldBucket(
            String period,
            BigDecimal inverterWh,
            BigDecimal meterWh,
            BigDecimal peakSunHours,
            BigDecimal pr,
            boolean irradianceGap
    ) {
    }

    /**
     * 电站发电量视图。
     *
     * @param ratedPowerWp    铭牌装机容量 Wp（PR 分母）；pv_stations 未登记时为 null
     * @param daily           按天汇总
     * @param monthly         按月汇总
     * @param inverterTotalWh INVERTER 来源合计 Wh
     * @param meterTotalWh    METER 来源合计 Wh
     * @param pr              区间整体性能比；无铭牌容量或全区间缺辐照度时为 null
     */
    public record StationYieldView(
            Long stationAssetId,
            BigDecimal ratedPowerWp,
            List<YieldBucket> daily,
            List<YieldBucket> monthly,
            BigDecimal inverterTotalWh,
            BigDecimal meterTotalWh,
            BigDecimal pr
    ) {
    }
}
