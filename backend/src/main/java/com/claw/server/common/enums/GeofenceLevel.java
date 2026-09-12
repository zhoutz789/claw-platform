package com.claw.server.common.enums;

/** 地面围栏级别（对应 claw.ground_geofences.level）。 */
public enum GeofenceLevel {
    /** 作业区（允许自驾）。 */
    WORK,
    /** 禁行区。 */
    NO_GO,
    /** 避让区。 */
    AVOID
}
