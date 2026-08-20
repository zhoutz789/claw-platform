package com.claw.server.common.enums;

/** 监管牌照状态（与 regulatory_licenses.status 一致）。 */
public enum LicenseStatus {
    OBTAINED,    // 已获
    APPLYING,    // 申请中
    REQUIRED,    // 必需要求（经结构规避则不单独申牌）
    NOT_REQUIRED // 不需
}
