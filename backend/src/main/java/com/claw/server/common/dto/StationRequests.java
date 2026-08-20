package com.claw.server.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 站点域请求（station 入参）。 */
public final class StationRequests {

    private StationRequests() {
    }

    /** 投放现货（投资者认购车辆投放至站点成为现货）。 */
    public record StockIn(
            @NotNull Long stationId,
            @NotBlank String skuCode,
            @Min(1) Integer qty) {
    }
}
