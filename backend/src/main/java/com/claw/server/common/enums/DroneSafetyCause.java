package com.claw.server.common.enums;

/** 无人机锁机触发原因。 */
public enum DroneSafetyCause {
    GEOFENCE_VIOLATION,  // 越界（进入 NFZ/RESTRICTED 禁限飞区）
    LOST_LINK,           // 失联（信号丢失超过阈值）
    LOW_BATTERY,         // 低电量（低于安全阈值）
    MANUAL               // 人工锁机
}
