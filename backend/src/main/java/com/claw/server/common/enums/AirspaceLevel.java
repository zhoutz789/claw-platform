package com.claw.server.common.enums;

/** 空域等级（对应 airspace_zones.level）。 */
public enum AirspaceLevel {
    OPERATIONAL,  // 可飞作业区
    RESTRICTED,   // 限飞区（需审批）
    NFZ           // 禁飞区（No-Fly Zone）
}
