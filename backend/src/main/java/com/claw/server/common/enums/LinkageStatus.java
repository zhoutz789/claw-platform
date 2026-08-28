package com.claw.server.common.enums;

/** 联动事件执行结果。 */
public enum LinkageStatus {
    DONE,     // 已执行
    SKIPPED,  // 未达阈值（评估通过但无需动作，预留）
    ERROR     // 执行异常（预留，由监听器回写）
}
