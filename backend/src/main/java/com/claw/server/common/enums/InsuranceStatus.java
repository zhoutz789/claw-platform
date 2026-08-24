package com.claw.server.common.enums;

/**
 * 保单状态（对应 V15 vehicle_insurance.status）。
 */
public enum InsuranceStatus {
    ACTIVE,
    LAPSED,
    CANCELLED,
    CLAIM_PENDING,
    EXPIRED
}
