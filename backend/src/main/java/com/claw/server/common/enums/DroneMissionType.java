package com.claw.server.common.enums;

/** 无人机作业类型（对应 drone_missions.mission_type）。 */
public enum DroneMissionType {
    SPRAY,       // 植保喷洒
    CARGO,       // 物流配送
    INSPECTION,  // 测绘巡检
    RESCUE       // 应急救援
}
