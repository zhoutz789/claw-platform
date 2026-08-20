package com.claw.server.common.enums;

/** 运营主体类型（治理层，与 tenants.operator_type 一致）。 */
public enum OperatorType {
    OWNED,     // 直营
    FRANCHISE, // 特许经营
    JV         // 合资
}
