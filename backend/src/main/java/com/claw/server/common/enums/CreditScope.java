package com.claw.server.common.enums;

/**
 * 授信额度口径（增量 C · Q17 推荐默认 STATION）。
 *
 * <dl>
 *   <dt>{@link #STATION}</dt>
 *   <dd>服务站维度总额度：同一个站下全部厂家的寄售设备名义货值合计不突破上限。</dd>
 *   <dt>{@link #MFG_STATION}</dt>
 *   <dd>「厂家 → 该站」逐对额度（Q17 备选，需新增 station_credit_limits 表，本轮未实现）。</dd>
 * </dl>
 *
 * <p>取值来自 {@code system_config.ONBOARDING_CREDIT_SCOPE}。
 */
public enum CreditScope {

    STATION,
    MFG_STATION
}
