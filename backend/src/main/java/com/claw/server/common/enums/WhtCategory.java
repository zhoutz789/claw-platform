package com.claw.server.common.enums;

/**
 * WHT 预扣税类别（T11 新增，对应 virtual_subaccount.wht_category / tax_withholding 计算口径）。
 *
 * <p>柬埔寨税法语境下的付款性质判定（设计附录 B.3 / C.2）：
 * <ul>
 *   <li>{@link #SERVICE} 服务费 —— 付个人的服务费 WHT 15%（Prakas 578 合规发票可豁免）；</li>
 *   <li>{@link #RENTAL} 动产/不动产租金（含资产使用权对价）—— WHT 10%；</li>
 *   <li>{@link #DIVIDEND} 股息/利润分配 —— WHT 0%；</li>
 *   <li>{@link #NONE} 不适用 —— WHT 0%（如平台自有收入，不作代扣）。</li>
 * </ul>
 */
public enum WhtCategory {
    /** 服务费。 */
    SERVICE,
    /** 租金（动产/不动产使用权对价）。 */
    RENTAL,
    /** 股息 / 利润分配。 */
    DIVIDEND,
    /** 不适用（无代扣义务）。 */
    NONE
}
