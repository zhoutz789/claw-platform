package com.claw.server.common.enums;

/**
 * 风控自动动作类型（对应 V10 operator_risk_events.auto_action）。
 */
public enum AutoAction {
    NONE,
    ALERT_ONLY,
    FREEZE_ACCOUNT,
    SUSPEND_OPERATOR
}
