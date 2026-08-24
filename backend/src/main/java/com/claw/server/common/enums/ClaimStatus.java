package com.claw.server.common.enums;

/**
 * 理赔状态（对应 V15 accident_claims.status）。
 */
public enum ClaimStatus {
    FILED,
    UNDER_REVIEW,
    ASSESSED,
    APPROVED,
    PAID,
    REJECTED,
    CLOSED
}
