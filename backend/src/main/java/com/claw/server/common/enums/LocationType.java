package com.claw.server.common.enums;

/**
 * 托管点位类型（对应 funds_location.location_type，V128 定义）。
 * 破产隔离在点位类型上显式建模：客户资金与公司自有资金物理/科目双隔离。
 */
public enum LocationType {
    /** 平台自有账户（公司自有资金，如平台服务费落点）。 */
    PLATFORM_OWN,
    /** 客户资金存管账户（客户资金，虚拟子户挂于此）。 */
    CLIENT_CUSTODY,
    /** 商家直连账户（货款直结通道方原生账户）。 */
    MERCHANT_DIRECT
}
