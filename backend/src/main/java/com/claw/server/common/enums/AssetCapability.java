package com.claw.server.common.enums;

/**
 * 资产能力标签（对应 claw.tasks.capability_required 与 claw.assets.capabilities CSV）。
 *
 * <p>发布任务时 {@code capability_required} 必须与该任务类型的预期能力一致；
 * 接单时资产 {@code capabilities} 必须包含该能力。
 */
public enum AssetCapability {
    /** 网约车（顺风车/快车）。 */
    RIDE_HAIL,
    /** 出租车。 */
    TAXI,
    /** 物流配送。 */
    LOGISTICS,
    /** 广告展示。 */
    AD_DISPLAY,
    /** 无人机作业。 */
    DRONE_OP,
    /** 换电（预留）。 */
    SWAP,
    /** 自主驾驶能力（无人车 autonomy 子域）。 */
    AUTONOMY
}
