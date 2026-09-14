package com.claw.server.domain.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 无人机资产收益报表（只读聚合，对应切片 4a / T14）。
 *
 * <p><b>数据源（与既有结算 100% 同口径，不新增资金池、不写任何账）：</b>
 * <ol>
 *   <li><b>任务收益（source=TASK_SETTLEMENT）</b> —— {@code TaskType.DRONE_OP} 任务经
 *       {@code TaskSettlementService.settle} 走 ledger 双记账后的接单方 C 方向分录；
 *       对账口径与 {@code TaskSettlementService#assetEarnings} 完全一致：
 *       {@code assignment.status == SETTLED} + bizRef {@code TASK-<taskId>-<assignmentId>}，
 *       只统计记在【接单方本人主账户】上的 C 分录（平台佣金记在平台内部户，不计入）。</li>
 *   <li><b>容量回佣（source=CAPACITY_REBATE）</b> ——
 *       {@code capacity_plans(capacity_type='PARALLEL')} 关联的
 *       {@code capacity_rebate_settlements.amount}（V71 回佣明细，已结算行）。</li>
 * </ol>
 *
 * <p>返回结构镜像 {@link VehicleEarningsReport}：分来源/子类型明细行 + 毛收入合计 +
 * 容量用户分成 + 平台侧分成。本报表完全只读。
 */
@Getter
@Builder
@AllArgsConstructor
public class DroneEarningsReport {

    /** 资产 ID（总览报表时为 null，改用 {@link #byAsset}）。 */
    private final Long assetId;

    /** 统计开始时间（含）。 */
    private final Instant from;

    /** 统计结束时间（含）。 */
    private final Instant to;

    /** 按来源 + 子类型拆分的收益明细行。 */
    private final List<EarningsLine> lines;

    /** 毛收入合计（任务收益 + 容量回佣）。 */
    private final BigDecimal grossTotal;

    /**
     * 容量用户分成金额 = PARALLEL 容量回佣合计（镜像车辆报表的 capacityUserShare 语义：
     * 回佣归容量定购用户）。
     */
    private final BigDecimal capacityUserShare;

    /** 平台侧/接单侧分成 = grossTotal − capacityUserShare（任务报酬归接单方）。 */
    private final BigDecimal platformShare;

    /** 币种（复用后端结算默认币种 "USD"，与 TaskSettlementService / LedgerService 一致）。 */
    private final String currency;

    /**
     * 单条收益明细行。
     *
     * @param source  收益来源：TASK_SETTLEMENT（任务报酬）或 CAPACITY_REBATE（容量回佣）
     * @param subtype 子类型：任务收益为作业类型（DroneMissionType，SPRAY/CARGO/INSPECTION/RESCUE），
     *                容量回佣为容量计划 ID（"plan-3"）
     * @param count   该行计入的凭证笔数
     * @param amount  该行金额合计（2 位小数，HALF_UP）
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class EarningsLine {

        /** 收益来源（TASK_SETTLEMENT / CAPACITY_REBATE）。 */
        private final String source;

        /** 子类型（作业类型或容量计划标识）。 */
        private final String subtype;

        /** 凭证笔数。 */
        private final Long count;

        /** 金额合计。 */
        private final BigDecimal amount;
    }

    /**
     * 总览报表的单资产小结。
     *
     * @param assetId            无人机资产 ID
     * @param taskEarningsTotal  任务报酬合计（归接单方）
     * @param rebateTotal        容量回佣合计（归容量定购用户）
     * @param grossTotal         毛收入合计
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class AssetSummary {

        /** 无人机资产 ID。 */
        private final Long assetId;

        /** 任务报酬合计。 */
        private final BigDecimal taskEarningsTotal;

        /** 容量回佣合计。 */
        private final BigDecimal rebateTotal;

        /** 毛收入合计。 */
        private final BigDecimal grossTotal;
    }

    /**
     * 总览报表（/earnings/overview 端点）：全部无人机资产在时间窗内的收益聚合。
     */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class Overview {

        /** 统计开始时间（含）。 */
        private final Instant from;

        /** 统计结束时间（含）。 */
        private final Instant to;

        /** 纳入统计的无人机资产数。 */
        private final Long assetCount;

        /** 按来源 + 子类型聚合的全网明细行。 */
        private final List<EarningsLine> lines;

        /** 分资产小结（按资产 ID 升序）。 */
        private final List<AssetSummary> byAsset;

        /** 任务报酬全网合计（归接单方）。 */
        private final BigDecimal taskEarningsTotal;

        /** 容量回佣全网合计（归容量定购用户）。 */
        private final BigDecimal rebateTotal;

        /** 毛收入全网合计。 */
        private final BigDecimal grossTotal;

        /** 币种（"USD"）。 */
        private final String currency;
    }
}
