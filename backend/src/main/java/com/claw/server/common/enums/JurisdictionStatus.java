package com.claw.server.common.enums;

/** 法域/国家运营状态（与 countries.status 一致）。全球一家，不设排除国。 */
public enum JurisdictionStatus {
    PILOT,    // 首个试点（柬埔寨）
    ACTIVE,   // 已运营 / 已开放为全球节点
    PLANNED   // 规划中（计划自营落地的运营节点）
}
