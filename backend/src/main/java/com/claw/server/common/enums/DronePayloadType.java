package com.claw.server.common.enums;

/** 无人机载荷类型（对应 drones.payload_type）。 */
public enum DronePayloadType {
    SPRAY,    // 植保喷洒
    CARGO,    // 物流货箱
    SLING,    // 吊运
    THERMAL,  // 巡检热成像
    RECON     // 测绘侦察
}
