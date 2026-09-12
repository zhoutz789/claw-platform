package com.claw.server.common.enums;

/** 广告计费模式（对应 claw.ad_campaigns.bid_mode 与 ad_play_logs.charge_mode）。 */
public enum AdBidMode {
    /** 每千次播放。 */
    CPM,
    /** 按点击（需交互屏）。 */
    CPC,
    /** 按次（每次播放固定价）。 */
    FLAT_PLAY,
    /** 按时段包段。 */
    FLAT_TIME
}
