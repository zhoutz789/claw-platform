package com.claw.server.common.enums;

/** 联动触发类型：一次遥测中具体何种信号驱动了联动。 */
public enum LinkageTriggerType {
    OPERATIONAL_SYNC,  // 常规运营同步（每次上报都会驱动资产档案更新 + 收益重算）
    LOW_SOC,           // 低电量
    FAULT,             // 故障码
    LOST_LINK,         // 失联
    GEOFENCE,          // 越界
    SOH_LOW,           // 健康度过低
    FLIGHT_OVER,       // 飞行时长超限
    USAGE              // 用量计费
}
