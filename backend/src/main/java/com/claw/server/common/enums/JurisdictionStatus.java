package com.claw.server.common.enums;

/** 法域/国家运营状态（与 countries.status 一致）。 */
public enum JurisdictionStatus {
    PILOT,    // 首个试点（柬埔寨）
    ACTIVE,   // 已运营
    PLANNED,  // 规划中
    EXCLUDED  // 暂不做（中国 / 发达国家）
}
