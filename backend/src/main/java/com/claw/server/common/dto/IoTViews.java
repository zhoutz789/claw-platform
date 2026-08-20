package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** IoT 域视图（iot 出参）。 */
public final class IoTViews {

    private IoTViews() {
    }

    /** 最新遥测。 */
    public record TelemetryView(
            Long assetId, Long deviceId,
            BigDecimal speed, BigDecimal soc, BigDecimal temp, BigDecimal humid,
            String faults, BigDecimal lat, BigDecimal lng, Instant reportedAt) {
    }

    /** 轨迹点。 */
    public record TrackView(
            Long assetId, Instant ts,
            BigDecimal lat, BigDecimal lng, BigDecimal speed, BigDecimal soc) {
    }
}
