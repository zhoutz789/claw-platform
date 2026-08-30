package com.claw.server.common.enums;

/** 寄售占有权状态（custody_records）。ACTIVE=在站持有；TRANSFERRED_OUT=已交接转出；RETURNED=回流；RELEASED=已释放（取货售罄）。 */
public enum CustodyStatus {
    ACTIVE,
    TRANSFERRED_OUT,
    RETURNED,
    RELEASED
}
