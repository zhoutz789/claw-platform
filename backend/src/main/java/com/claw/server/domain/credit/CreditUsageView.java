package com.claw.server.domain.credit;

import java.math.BigDecimal;

/**
 * 授信额度占用视图（增量 C · O 增补 / §4.1）。
 *
 * @param usedValue    当前已占用货值（在途 + 在库寄售）
 * @param creditLimit  额度上限（NULL 表示未设档位，不校验）
 * @param usageRatio   占用率（0–1），无额度时为 null
 * @param tierCode     档位码（BASIC / STANDARD / PREMIUM）
 * @param tierName     档位名
 * @param deviceCount  当前占用中的设备数
 * @param overLimit    是否超额（降档后存量超限，仅提示不阻断）
 * @param warn         占用率是否达到告警阈值（ONBOARDING_CREDIT_WARN_RATIO，默认 0.8）
 */
public record CreditUsageView(
        BigDecimal usedValue,
        BigDecimal creditLimit,
        BigDecimal usageRatio,
        String tierCode,
        String tierName,
        Integer deviceCount,
        Boolean overLimit,
        Boolean warn) {
}
