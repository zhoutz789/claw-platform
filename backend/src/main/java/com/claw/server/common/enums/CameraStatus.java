package com.claw.server.common.enums;

/**
 * 摄像头 / 资产监控状态（对应 camera_stream.status）。
 * 与资产作业语义对齐：作业中 / 关闭 / 待作业 / 离线 / 故障。
 */
public enum CameraStatus {
    WORKING,   // 作业中（在线推流中）
    CLOSED,    // 关闭（主动停用）
    PENDING,   // 待作业（已注册未启用）
    OFFLINE,   // 离线（失联）
    FAULT      // 故障
}
