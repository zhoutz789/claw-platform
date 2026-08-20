package com.claw.server.common.enums;

/** 合作伙伴类型（治理层分润，与 partner_programs.partner_type 一致）。 */
public enum PartnerType {
    BANK,             // 银行 / 清算（资金路径）
    IDENTITY,         // 国家数字身份局
    ENERGY_REGULATOR, // 能源监管局 / 电力局
    FLEET,            // 车队 / 出行平台（PassApp 等）
    RESELLER          // 本地加盟 / 渠道分销
}
