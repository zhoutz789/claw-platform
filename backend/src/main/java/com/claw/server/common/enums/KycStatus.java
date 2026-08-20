package com.claw.server.common.enums;

/** KYC 认证状态（对应 users.kyc_status / kyc_records.status）。 */
public enum KycStatus {
    PENDING,
    VERIFIED,
    REJECTED
}
