package com.claw.server.common.enums;

/**
 * 站长保证金状态（对应 V10 operator_bonds.status）。
 */
public enum BondStatus {
    PENDING,
    SUFFICIENT,
    SHORTFALL,
    FROZEN
}
