package com.claw.server.common.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

/** IoT 域请求（iot 入参）。 */
public final class IoTRequests {

    private IoTRequests() {
    }

    /** 遥测/轨迹上报（老 BMS 链路，REST 模拟 MQTT dev/{id}/telemetry 上报）。 */
    public record TelemetryReport(
            @NotBlank String imei,
            BigDecimal speed,
            BigDecimal soc,
            BigDecimal temp,
            BigDecimal humid,
            String faults,
            BigDecimal lat,
            BigDecimal lng,
            BigDecimal soh) {
    }

    /** 定位上报（车辆契约 location 报文）。字段名与选型书 JSON 一致。 */
    public record VehicleLocation(
            @NotBlank String deviceId,
            BigDecimal lat,
            BigDecimal lng,
            BigDecimal speed,
            BigDecimal course,
            BigDecimal alt,
            Integer acc,
            BigDecimal battery,
            Integer rssi) {
    }

    /** 状态/事件上报（车辆契约 status 报文）。 */
    public record VehicleStatus(
            @NotBlank String deviceId,
            Integer relay,
            Integer door,
            Integer vib,
            BigDecimal temp,
            List<String> alarms) {
    }

    /** 指令回执（车辆契约 cmd_ack 报文）。 */
    public record CommandAck(
            String deviceId,
            @NotBlank String cmdId,
            String result,
            String detail) {
    }

    /** 下发指令请求（平台 → 设备）。 */
    public record IssueCommand(String action, IssueCommandParams params) {
    }

    /** 下行指令参数（选型书 4.3）：state + safeCond 安全条件。 */
    public record IssueCommandParams(Integer state, SafeCond safeCond) {
    }

    /** 远程开关安全条件：仅熄火(accMustOff)且低速(maxSpeed)允许执行。 */
    public record SafeCond(Integer maxSpeed, Boolean accMustOff) {
    }

    /** 设备注册（平台分配 deviceNo + secret + 二维码；对应选型书"出厂烧录"）。 */
    public record RegisterDeviceRequest(Long assetId, String deviceNo, String secret) {
    }
}
