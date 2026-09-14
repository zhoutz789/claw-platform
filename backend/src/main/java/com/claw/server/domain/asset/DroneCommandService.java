package com.claw.server.domain.asset;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTViews;
import com.claw.server.common.enums.DroneCommandType;
import com.claw.server.domain.iot.Device;
import com.claw.server.domain.iot.DeviceCommandService;
import com.claw.server.domain.iot.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无人机远控指令服务（切片 1）：把「远程启动 / 返航 / 悬停 / 降落 / 暂停 / 恢复 / 取消任务 /
 * 围栏下发 / OTA」等无人机语义，解析为其 FCU 设备的下行指令，复用 iot 域既有
 * {@link DeviceCommandService}（带签名 + 防重放 + EMQX 下发 + {@code device_commands} 指令日志，
 * EMQX 未启用时仅落库不下发）。
 *
 * <p>设计意图（§2.2 / §3.8）：无人机远控复用与车辆/充电桩同一套「抽象指令通道」，本服务只做
 * 「无人机资产 → FCU 设备 → 下行 action」的语义映射，<b>不另造指令日志表</b>（与
 * {@link VehicleCommandService} 完全一致）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DroneCommandService {

    private static final String FCU_TYPE = "DRONE_FCU";

    private final DeviceRepository deviceRepository;
    private final DeviceCommandService deviceCommandService;

    /** 远程启动 / 起飞。 */
    @Transactional
    public IoTViews.CommandView remoteStart(Long assetId) {
        return issue(assetId, DroneCommandType.REMOTE_START, Map.of());
    }

    /** 返航。 */
    @Transactional
    public IoTViews.CommandView returnHome(Long assetId) {
        return issue(assetId, DroneCommandType.RETURN_HOME, Map.of());
    }

    /** 悬停。 */
    @Transactional
    public IoTViews.CommandView hold(Long assetId) {
        return issue(assetId, DroneCommandType.HOLD, Map.of());
    }

    /** 降落。 */
    @Transactional
    public IoTViews.CommandView land(Long assetId) {
        return issue(assetId, DroneCommandType.LAND, Map.of());
    }

    /** 暂停作业。 */
    @Transactional
    public IoTViews.CommandView pause(Long assetId) {
        return issue(assetId, DroneCommandType.PAUSE, Map.of());
    }

    /** 恢复作业。 */
    @Transactional
    public IoTViews.CommandView resume(Long assetId) {
        return issue(assetId, DroneCommandType.RESUME, Map.of());
    }

    /** 取消任务。 */
    @Transactional
    public IoTViews.CommandView cancelTask(Long assetId) {
        return issue(assetId, DroneCommandType.CANCEL_TASK, Map.of());
    }

    /** 地理围栏下发（geofence 可传多边形 / 围栏对象；空则不携带参数）。 */
    @Transactional
    public IoTViews.CommandView setGeofence(Long assetId, Object geofence) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("geofence", geofence);
        return issue(assetId, DroneCommandType.SET_GEOFENCE, params);
    }

    /** 固件 OTA 升级。 */
    @Transactional
    public IoTViews.CommandView ota(Long assetId) {
        return issue(assetId, DroneCommandType.OTA, Map.of());
    }

    /**
     * 通用下发入口：解析无人机 FCU 设备并按指令类型下发（控制层字符串指令也用此路径）。
     *
     * @param assetId 无人机资产 ID
     * @param type    指令类型
     * @param params  下行参数（不可为 null，无参数传 {@code Map.of()}）
     * @return 指令视图（含 cmdId / 状态）
     */
    @Transactional
    public IoTViews.CommandView issue(Long assetId, DroneCommandType type, Map<String, Object> params) {
        Device fcu = resolveFcu(assetId);
        return deviceCommandService.issue(fcu.getDeviceNo(), type.getAction(), params);
    }

    /** 无人机资产 → 其 DRONE_FCU 控制器（一台机可挂多设备，取 FCU 作为指令执行端）。 */
    private Device resolveFcu(Long assetId) {
        List<Device> devices = deviceRepository.findByAssetId(assetId);
        return devices.stream()
                .filter(d -> FCU_TYPE.equals(d.getDeviceType()))
                .findFirst()
                .orElseThrow(() -> BizException.notFound("error.drone.fcu.not.found:" + assetId));
    }
}
