package com.claw.server.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** IoT 域视图（iot 出参）。 */
public final class IoTViews {

    private IoTViews() {
    }

    /** 最新遥测（老 BMS 链路）。 */
    public record TelemetryView(
            Long assetId, Long deviceId,
            BigDecimal speed, BigDecimal soc, BigDecimal soh, BigDecimal temp, BigDecimal humid,
            String faults, BigDecimal lat, BigDecimal lng, Instant reportedAt) {
    }

    /** 设备联动事件（四向闭环审计）。 */
    public record LinkageView(
            Long id, Long assetId, Long deviceId,
            String direction, String triggerType, String payload, String status,
            Instant triggeredAt) {
    }

    /** 轨迹点。 */
    public record TrackView(
            Long assetId, Instant ts,
            BigDecimal lat, BigDecimal lng, BigDecimal speed, BigDecimal soc) {
    }

    /** 设备最新状态（车辆契约）。 */
    public record DeviceStatusView(
            Long deviceId, String deviceNo,
            Integer relayState, Integer acc, BigDecimal batteryVoltage, Integer rssi,
            Integer doorState, Integer vibState, String alarms, Instant reportedAt) {
    }

    /** 指令视图（下发 + 回执关联）。 */
    public record CommandView(
            Long id, String deviceNo, String action, String cmdId,
            String status, String result, String detail, Instant createdAt, Instant ackedAt) {
    }

    /** 注册设备视图（返回 deviceNo + secret + 二维码载体）。 */
    public record DeviceView(
            Long id, String deviceNo, String deviceType, String imei,
            String status, String qrPayload, Instant lastOnlineAt) {
    }
}
