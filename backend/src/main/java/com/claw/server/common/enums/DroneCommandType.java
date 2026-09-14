package com.claw.server.common.enums;

/**
 * 无人机控制指令类型（平台远控语义层）。
 *
 * <p>每个枚举映射到 iot 域 {@code DeviceCommandService} 的下行 {@code action} 字符串，
 * 由 {@code DroneCommandService} 负责「无人机资产 → FCU 设备 → 下行报文」的解析与下发。
 * 结构与 {@link VehicleCommandType} 一致；action 统一以 {@code drone.} 前缀区分机型域。
 */
public enum DroneCommandType {
    /** 远程启动 / 起飞。 */
    REMOTE_START("drone.remote_start"),
    /** 返航。 */
    RETURN_HOME("drone.return_home"),
    /** 悬停。 */
    HOLD("drone.hold"),
    /** 降落。 */
    LAND("drone.land"),
    /** 暂停作业。 */
    PAUSE("drone.pause"),
    /** 恢复作业。 */
    RESUME("drone.resume"),
    /** 取消任务。 */
    CANCEL_TASK("drone.cancel_task"),
    /** 地理围栏下发（参数含 geofence / polygon）。 */
    SET_GEOFENCE("drone.set_geofence"),
    /** 固件 OTA 升级。 */
    OTA("drone.ota");

    private final String action;

    DroneCommandType(String action) {
        this.action = action;
    }

    /** iot 下行指令 action 字符串。 */
    public String getAction() {
        return action;
    }

    /**
     * 由指令名或 action 字符串解析枚举（大小写不敏感）。
     *
     * <p>同时接受枚举名（如 {@code REMOTE_START}）与下行 action（如 {@code drone.remote_start}），
     * 便于后台以字符串透传指令。无法识别时抛 {@link IllegalArgumentException}，由控制层转
     * {@code BizException.invalidParam("error.drone.command.invalid")}。
     *
     * @param code 指令名或 action 字符串
     * @return 对应的指令类型
     * @throws IllegalArgumentException 当 code 为空或不匹配任何指令类型
     */
    public static DroneCommandType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("drone command is blank");
        }
        for (DroneCommandType type : values()) {
            if (type.name().equalsIgnoreCase(code) || type.action.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown drone command: " + code);
    }
}
