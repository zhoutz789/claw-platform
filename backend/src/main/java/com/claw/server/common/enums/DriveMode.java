package com.claw.server.common.enums;

/** 无人车驾驶模式（对应 claw.autonomy_modules.drive_mode）。 */
public enum DriveMode {
    /** 辅助驾驶（人主导）。 */
    ASSISTED,
    /** 远程接管（云端/安全员实时介入）。 */
    TELEOP,
    /** 全自主。 */
    FULL
}
