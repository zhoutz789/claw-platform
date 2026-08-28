package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.IoTRequests;
import com.claw.server.common.dto.IoTViews;
import com.claw.server.common.enums.LinkageDirection;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceCommandService;
import com.claw.server.domain.iot.DeviceRepository;
import com.claw.server.domain.iot.IoTService;
import com.claw.server.domain.iot.MqttSigner;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * IoT 域接口（S5）：遥测/轨迹上报与查询 + 车辆终端契约（对齐选型书）。
 *
 * <p>老 BMS 链路：
 *   POST /iot/telemetry                    遥测上报（imei 帧）
 *   GET  /assets/{assetId}/telemetry       资产最新遥测
 *   GET  /assets/{assetId}/tracks          资产轨迹（?from=&to=）
 *   GET  /assets/{assetId}/linkage         资产联动事件（?direction=）
 *
 * <p>车辆终端契约：
 *   POST /iot/devices                       注册车辆 TCU（平台分配 deviceNo + secret + 二维码）
 *   POST /iot/devices/{deviceNo}/command    下发指令（relay/lock/config/reboot/ota）
 *   GET  /iot/devices/{deviceNo}/status     查询设备最新状态
 *   GET  /iot/devices/{deviceNo}/commands   查询指令历史
 *   POST /iot/devices/{deviceNo}/location   模拟设备定位上报（无 Broker 联调用）
 *   POST /iot/devices/{deviceNo}/status     模拟设备状态上报
 *   POST /iot/devices/{deviceNo}/ack         模拟设备指令回执
 *
 * <p>注：设备上报/注册当前为开放接口（与既有遥测接口一致），生产环境应加设备证书/平台鉴权。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class IoTController {

    private final IoTService iotService;
    private final DeviceCommandService deviceCommandService;
    private final DeviceRepository deviceRepository;

    // ---------------- 老 BMS 链路 ----------------
    @PostMapping("/iot/telemetry")
    public ApiResult<IoTViews.TelemetryView> reportTelemetry(@Valid @RequestBody IoTRequests.TelemetryReport req) {
        return ApiResult.ok(iotService.reportTelemetry(req));
    }

    @GetMapping("/assets/{assetId}/telemetry")
    public ApiResult<IoTViews.TelemetryView> latestTelemetry(@PathVariable Long assetId) {
        return ApiResult.ok(iotService.latest(assetId));
    }

    @GetMapping("/assets/{assetId}/tracks")
    public ApiResult<List<IoTViews.TrackView>> tracks(@PathVariable Long assetId,
                                                      @RequestParam(required = false) Instant from,
                                                      @RequestParam(required = false) Instant to) {
        return ApiResult.ok(iotService.tracks(assetId, from, to));
    }

    @GetMapping("/assets/{assetId}/linkage")
    public ApiResult<List<IoTViews.LinkageView>> linkage(@PathVariable Long assetId,
                                                         @RequestParam(required = false) String direction) {
        LinkageDirection dir = direction != null && !direction.isBlank()
                ? LinkageDirection.valueOf(direction) : null;
        return ApiResult.ok(iotService.linkageEvents(assetId, dir));
    }

    // ---------------- 车辆终端契约 ----------------
    /** 注册车辆 TCU：平台分配 deviceNo + secret + 二维码载体。 */
    @PostMapping("/iot/devices")
    public ApiResult<IoTViews.DeviceView> register(@Valid @RequestBody IoTRequests.RegisterDeviceRequest req) {
        String deviceNo = (req.deviceNo() != null && !req.deviceNo().isBlank())
                ? req.deviceNo() : "CLAW-VT-" + Instant.now().toEpochMilli();
        String secret = (req.secret() != null && !req.secret().isBlank())
                ? req.secret() : MqttSigner.nonce() + MqttSigner.nonce();

        Device device = Device.builder()
                .assetId(req.assetId())
                .deviceType("VEHICLE_TCU")
                .deviceNo(deviceNo)
                .secret(secret)
                .qrPayload(deviceNo)
                .status("ACTIVE")
                .tenantId(1L)
                .build();
        deviceRepository.save(device);

        return ApiResult.ok(new IoTViews.DeviceView(device.getId(), deviceNo, device.getDeviceType(),
                device.getImei(), device.getStatus(), device.getQrPayload(), device.getLastOnlineAt()));
    }

    /** 下发指令（签名 + 防重放 + 安全条件在 DeviceCommandService 内完成）。 */
    @PostMapping("/iot/devices/{deviceNo}/command")
    public ApiResult<IoTViews.CommandView> issueCommand(@PathVariable String deviceNo,
                                                       @Valid @RequestBody IoTRequests.IssueCommand req) {
        return ApiResult.ok(deviceCommandService.issue(deviceNo, req));
    }

    /** 查询设备最新状态。 */
    @GetMapping("/iot/devices/{deviceNo}/status")
    public ApiResult<IoTViews.DeviceStatusView> deviceStatus(@PathVariable String deviceNo) {
        return ApiResult.ok(iotService.deviceStatus(deviceNo));
    }

    /** 查询指令历史。 */
    @GetMapping("/iot/devices/{deviceNo}/commands")
    public ApiResult<List<IoTViews.CommandView>> commandHistory(@PathVariable String deviceNo) {
        return ApiResult.ok(deviceCommandService.history(deviceNo));
    }

    // ---------------- 无 Broker 联调：模拟设备上行 ----------------
    @PostMapping("/iot/devices/{deviceNo}/location")
    public ApiResult<Void> simLocation(@PathVariable String deviceNo,
                                       @Valid @RequestBody IoTRequests.VehicleLocation body) {
        iotService.reportLocation(new IoTRequests.VehicleLocation(deviceNo, body.lat(), body.lng(),
                body.speed(), body.course(), body.alt(), body.acc(), body.battery(), body.rssi()));
        return ApiResult.ok(null);
    }

    @PostMapping("/iot/devices/{deviceNo}/status")
    public ApiResult<Void> simStatus(@PathVariable String deviceNo,
                                     @Valid @RequestBody IoTRequests.VehicleStatus body) {
        iotService.reportStatus(new IoTRequests.VehicleStatus(deviceNo, body.relay(), body.door(),
                body.vib(), body.temp(), body.alarms()));
        return ApiResult.ok(null);
    }

    @PostMapping("/iot/devices/{deviceNo}/ack")
    public ApiResult<Void> simAck(@PathVariable String deviceNo,
                                  @Valid @RequestBody IoTRequests.CommandAck body) {
        deviceCommandService.handleAck(new IoTRequests.CommandAck(deviceNo, body.cmdId(),
                body.result(), body.detail()));
        return ApiResult.ok(null);
    }
}
