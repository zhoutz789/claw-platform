package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTViews;
import com.claw.server.common.enums.VehicleCommandType;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceCommandService;
import com.claw.server.domain.iot.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 车辆控制指令服务（T3 平台控车核心）：把"锁车/解锁/远程启停/空调/复位/围栏"等车辆语义，
 * 解析为具体车辆的 TCU 设备下行指令，复用 iot 域既有 {@link DeviceCommandService}
 * （带签名 + 防重放 + EMQX 下发 + {@code device_commands} 指令日志，EMQX 未启用时仅落库不下发）。
 *
 * <p>设计意图（§2.2）：车辆控制复用无人机/充电桩的同一套"抽象指令通道"，本服务只做
 * "车辆资产 → TCU 设备 → 下行 action" 的语义映射，不另造指令日志表。
 * 断缴锁车由风控域经 {@code PaymentDefaultLockTrigger} 调 {@link #lockForPaymentDefault(Long)} 触发。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleCommandService {

    private static final String TCU_TYPE = "VEHICLE_TCU";

    private final DeviceRepository deviceRepository;
    private final DeviceCommandService deviceCommandService;

    /** 锁车（普通 / 手动）。 */
    @Transactional
    public IoTViews.CommandView lock(Long assetId) {
        return issue(assetId, VehicleCommandType.LOCK, Map.of());
    }

    /** 解锁。 */
    @Transactional
    public IoTViews.CommandView unlock(Long assetId) {
        return issue(assetId, VehicleCommandType.UNLOCK, Map.of());
    }

    /** 远程启动。 */
    @Transactional
    public IoTViews.CommandView remoteStart(Long assetId) {
        return issue(assetId, VehicleCommandType.REMOTE_START, Map.of());
    }

    /** 远程熄火。 */
    @Transactional
    public IoTViews.CommandView remoteStop(Long assetId) {
        return issue(assetId, VehicleCommandType.REMOTE_STOP, Map.of());
    }

    /** 空调设置（占位字段，固件支持后做实；tempC 为摄氏度）。 */
    @Transactional
    public IoTViews.CommandView setAc(Long assetId, double tempC) {
        return issue(assetId, VehicleCommandType.SET_AC, Map.of("tempC", tempC));
    }

    /** 复位 / 重启 TCU。 */
    @Transactional
    public IoTViews.CommandView reset(Long assetId) {
        return issue(assetId, VehicleCommandType.RESET, Map.of());
    }

    /** 围栏下发。 */
    @Transactional
    public IoTViews.CommandView setGeofence(Long assetId, Object polygon) {
        return issue(assetId, VehicleCommandType.SET_GEOFENCE, Map.of("polygon", polygon));
    }

    /**
     * 断缴锁车（风控挂钩入口）：对车辆下发 LOCK，指令参数标注欠费原因。
     * 由风控域 {@code PaymentDefaultLockTrigger} 调用。
     */
    @Transactional
    public IoTViews.CommandView lockForPaymentDefault(Long assetId) {
        log.warn("[VEHICLE-LOCK] 断缴锁车触发 assetId={}", assetId);
        return issue(assetId, VehicleCommandType.LOCK, Map.of("reason", "PAYMENT_DEFAULT"));
    }

    /** 解析车辆 TCU 设备并下发指令。 */
    private IoTViews.CommandView issue(Long assetId, VehicleCommandType type, Map<String, Object> params) {
        Device tcu = resolveTcu(assetId);
        return deviceCommandService.issue(tcu.getDeviceNo(), type.getAction(), params);
    }

    /** 车辆资产 → 其 VEHICLE_TCU 控制器（一台车可挂多设备，取 TCU 作为指令执行端）。 */
    private Device resolveTcu(Long assetId) {
        List<Device> devices = deviceRepository.findByAssetId(assetId);
        return devices.stream()
                .filter(d -> TCU_TYPE.equals(d.getDeviceType()))
                .findFirst()
                .orElseThrow(() -> BizException.notFound("error.vehicle.tcu.not.found:" + assetId));
    }
}
