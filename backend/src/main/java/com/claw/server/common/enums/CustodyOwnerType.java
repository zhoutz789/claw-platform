package com.claw.server.common.enums;

/**
 * 虚拟子户持有方类型（对应 virtual_subaccount.owner_type，V128 定义）。
 * 用户/商家等不占用真实银行账户，只在托管点位下开逻辑子户。
 */
public enum CustodyOwnerType {
    /** 平台用户（终端消费者）。 */
    USER,
    /** 服务站（站方佣金收款方）。 */
    STATION,
    /** 厂家（寄售货款收款方）。 */
    MANUFACTURER,
    /** 投资人（共享池分成方）。 */
    INVESTOR,
    /** 保险公司（共享池分成方）。 */
    INSURER,
    /** 物流公司（配送费收款方）。 */
    LOGISTICS
}
