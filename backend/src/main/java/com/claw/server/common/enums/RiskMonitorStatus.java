package com.claw.server.common.enums;

/**
 * 风控监控状态（对应 V16 station_risk_monitor.status）。
 */
public enum RiskMonitorStatus {
    NORMAL,
    WARNING,
    CRITICAL,
    CIRCUIT_BREAK,
    RESOLVED
}
