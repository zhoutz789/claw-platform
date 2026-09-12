package com.claw.server.common.enums;

/**
 * 车辆控制指令类型（平台控车语义层）。
 *
 * <p>每个枚举映射到 iot 域 {@code DeviceCommandService} 的下行 {@code action} 字符串，
 * 由 {@code VehicleCommandService} 负责车辆资产 → TCU 设备 → 下行报文的解析与下发。
 */
public enum VehicleCommandType {
    /** 断缴锁车 / 普通锁车。 */
    LOCK("lock"),
    /** 解锁。 */
    UNLOCK("unlock"),
    /** 远程启动。 */
    REMOTE_START("remote_start"),
    /** 远程熄火。 */
    REMOTE_STOP("remote_stop"),
    /** 空调设置（参数含 tempC）。 */
    SET_AC("set_ac"),
    /** 复位 / 重启控制器。 */
    RESET("reset"),
    /** 围栏下发（参数含 polygon / geofence）。 */
    SET_GEOFENCE("set_geofence");

    private final String action;

    VehicleCommandType(String action) {
        this.action = action;
    }

    /** iot 下行指令 action 字符串。 */
    public String getAction() {
        return action;
    }
}
