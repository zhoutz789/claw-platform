package com.claw.server.domain.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 车辆资产收益报表（容量定购用户视角，基于既有运营/结算数据）。
 *
 * <p>给定一条容量定购（用户 → 资产池 → 收益分成），产出该资产在给定时间窗内的
 * 分运营类型营收明细、毛收入合计，以及容量用户的分成金额。本报表为只读，
 * 直接复用 {@code claw.asset_vehicle_ops} 的 {@code revenue} 聚合（不重建结算）。
 */
@Getter
@Builder
@AllArgsConstructor
public class VehicleEarningsReport {

    /** 资产 ID。 */
    private final Long assetId;

    /** 按运营类型（opType）拆分的收益明细行。 */
    private final List<EarningsLine> lines;

    /** 毛收入合计（所有运营类型的 revenue 之和）。 */
    private final BigDecimal grossTotal;

    /** 容量用户的分成金额 = grossTotal × shareRatio。 */
    private final BigDecimal capacityUserShare;

    /** 平台分成金额 = grossTotal − capacityUserShare（如存在平台分成）。可为 null。 */
    private final BigDecimal platformShare;

    /** 币种（复用后端结算默认币种 "USD"）。 */
    private final String currency;

    /** 单条运营类型收益明细。 */
    @Getter
    @Builder
    @AllArgsConstructor
    public static class EarningsLine {

        /** 运营类型（VehicleOpType 枚举名，如 PASSENGER / LOGISTICS）。 */
        private final String opType;

        /** 该类型的运营次数。 */
        private final Long count;

        /** 该类型的营收合计。 */
        private final BigDecimal amount;
    }
}
