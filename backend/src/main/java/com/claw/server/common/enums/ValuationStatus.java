package com.claw.server.common.enums;

/**
 * 残值评估状态（对应 V13 residual_valuations.status）。
 */
public enum ValuationStatus {
    PENDING,
    SYSTEM_DONE,
    STATION_DONE,
    THIRD_PARTY_DONE,
    FINALIZED,
    EXPIRED,
    RECOVERED
}
