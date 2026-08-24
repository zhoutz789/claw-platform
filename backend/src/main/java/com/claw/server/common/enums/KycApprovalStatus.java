package com.claw.server.common.enums;

/**
 * KYC 审批状态（对应 V10 operator_kyc_records.status）。
 */
public enum KycApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
}
