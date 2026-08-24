package com.claw.server.common.enums;

/**
 * 风控事件类型（对应 V10 operator_risk_events.event_type）。
 */
public enum RiskEventType {
    BOND_SHORTFALL,
    ASSET_MISSING,
    RECONCILIATION_FAIL,
    COMPLAINT_SPIKE,
    UNUSUAL_TRANSACTION,
    FRAUD_SUSPECTED
}
