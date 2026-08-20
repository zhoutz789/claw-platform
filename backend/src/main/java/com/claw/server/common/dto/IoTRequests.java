package com.claw.server.common.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/** IoT 域请求（iot 入参）。 */
public final class IoTRequests {

    private IoTRequests() {
    }

    /** 遥测/轨迹上报（REST 模拟 MQTT dev/{id}/telemetry 上报）。 */
    public record TelemetryReport(
            @NotBlank String imei,
            BigDecimal speed,
            BigDecimal soc,
            BigDecimal temp,
            BigDecimal humid,
            String faults,
            BigDecimal lat,
            BigDecimal lng) {
    }
}
