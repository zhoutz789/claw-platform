package com.claw.server.common.dto;

import com.claw.server.common.enums.SwapStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/** 换电域请求（swap 入参）。 */
public final class SwapRequests {

    private SwapRequests() {
    }

    /** 创建换电单：选站 + 车辆/旧电池（可选）+ 协议版本 + 预估度数。 */
    public record Create(
            @NotNull Long stationId,
            Long vehicleId,
            Long batteryInId,
            String protocolVer,
            @Min(1) BigDecimal estKwh) {
    }

    /** 结算：实际用量（缺省用预估）与归还电量。 */
    public record Settle(
            @Min(1) BigDecimal actualKwh,
            BigDecimal socEnd) {
    }

    /** 取消原因。 */
    public record Cancel(
            String reason) {
    }
}
