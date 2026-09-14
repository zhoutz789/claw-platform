package com.claw.server.common.enums;

/**
 * 收款方纳税人状态（T11 新增，对应 virtual_subaccount.taxpayer_status）。
 *
 * <p>决定平台向该收款方付款时是否代扣 WHT 及适用税率（设计附录 B.3 / B.5 / C.2）：
 * <ul>
 *   <li>{@link #REGISTERED} 已登记纳税人且开具合规发票 —— Prakas 578(2024) 豁免，WHT=0；</li>
 *   <li>{@link #UNREGISTERED} 未登记纳税人 —— 按 whtCategory 法定税率代扣；</li>
 *   <li>{@link #INDIVIDUAL} 个人 —— 服务费 15% / 租金 10%；</li>
 *   <li>{@link #NON_RESIDENT} 非居民（境外母公司/供应商）—— 默认 14%（中柬协定 10% 可配置）。</li>
 * </ul>
 */
public enum TaxpayerStatus {
    /** 已登记纳税人（合规发票豁免）。 */
    REGISTERED,
    /** 未登记纳税人。 */
    UNREGISTERED,
    /** 个人收款方。 */
    INDIVIDUAL,
    /** 非居民（境外）。 */
    NON_RESIDENT
}
