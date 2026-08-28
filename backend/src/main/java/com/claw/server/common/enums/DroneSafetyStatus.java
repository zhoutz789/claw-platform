package com.claw.server.common.enums;

/** 无人机飞行安全态（类比车辆断缴锁车）：正常可放飞 / 锁机禁飞。 */
public enum DroneSafetyStatus {
    NORMAL,  // 可放飞
    LOCKED   // 锁机：越界/失联/低电量等风险触发，禁止起飞
}
