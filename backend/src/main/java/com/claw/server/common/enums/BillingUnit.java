package com.claw.server.common.enums;

/**
 * 作业计费单位（无人机作业计量 / 分账计量维度）。
 * 与作业模版 {@code ops_template} 的计费口径对应：按亩 / 按公里 / 按趟 / 按小时。
 */
public enum BillingUnit {
    /** 按公顷 / 亩（农业植保）。 */
    PER_HECTARE,
    /** 按公里（巡检 / 测绘里程）。 */
    PER_KM,
    /** 按趟（低空物流）。 */
    PER_TRIP,
    /** 按小时（救援 / 巡逻时长）。 */
    PER_HOUR
}
