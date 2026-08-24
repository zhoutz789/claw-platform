package com.claw.server.common.enums;

/**
 * 风控监控指标类型（对应 V16 station_risk_monitor.metric_type）。
 */
public enum RiskMetricType {
    BOND_SHORTFALL,
    ASSET_MISMATCH,
    RECONCILIATION_FAIL,
    COMPLAINT_SPIKE,
    TRANSACTION_ANOMALY,
    OFF_HOURS_ACTIVITY,
    FUND_FLOW_ANOMALY
}
