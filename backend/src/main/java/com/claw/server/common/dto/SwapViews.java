package com.claw.server.common.dto;

import com.claw.server.common.enums.SwapStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** 换电域视图（swap 出参）。 */
public final class SwapViews {

    private SwapViews() {
    }

    /** 计价预览（锁版价：电费 + 服务费，按度）。 */
    public record QuoteView(
            BigDecimal elecRate,      // 电费单价 $/kWh（光伏锁版 0.12）
            BigDecimal serviceRate,   // 服务费单价 $/kWh（锁版 0.32）
            BigDecimal totalRate,     // 合计单价
            BigDecimal kwh,           // 度数
            BigDecimal elecFee,       // 电费
            BigDecimal serviceFee,    // 服务费
            BigDecimal total) {       // 合计
    }

    /** 换电订单。 */
    public record SwapOrderView(
            String orderNo,
            Long userId,
            Long stationId,
            String stationName,
            Long vehicleId,
            Long batteryOutId,
            Long batteryInId,
            SwapStatus status,
            String protocolVer,
            BigDecimal batteryDeposit,
            BigDecimal oldBatteryDeposit,
            BigDecimal estKwh,
            BigDecimal estElecFee,
            BigDecimal estServiceFee,
            BigDecimal estTotal,
            BigDecimal actualKwh,
            BigDecimal actualElecFee,
            BigDecimal actualServiceFee,
            BigDecimal actualTotal,
            BigDecimal socStart,
            BigDecimal socEnd,
            String settleStatus,
            String cancelReason,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 订单事件。 */
    public record EventView(
            String orderNo,
            String event,
            Long operatorId,
            String payload,
            Instant createdAt) {
    }
}
