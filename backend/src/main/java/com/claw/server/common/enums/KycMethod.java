package com.claw.server.common.enums;

/** KYC 认证路径（对应 kyc_records.method）。 */
public enum KycMethod {
    MANUAL,      // 平台人工/短信实名
    CAMDIGIKEY   // 国家数字身份 OAuth2.0 eKYC
}
