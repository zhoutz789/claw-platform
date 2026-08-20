package com.claw.server.common.enums;

/** 支付提供方类型（与 payment_providers.provider_type 一致）。 */
public enum ProviderType {
    BANK,     // 银行/代理网络（ABA / Wing）
    QR,       // 国家二维码（KHQR）
    CLEARING, // 国家清算（Bakong）
    CASH      // 现金兜底
}
