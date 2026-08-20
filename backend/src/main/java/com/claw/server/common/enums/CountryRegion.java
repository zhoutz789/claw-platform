package com.claw.server.common.enums;

/** 扩展路线图分区（与 countries.region 一致）。 */
public enum CountryRegion {
    SEA,           // 东南亚（波次 2）
    WEST_ASIA,     // 西亚（波次 3，剔除高收入海湾）
    AFRICA,        // 非洲（波次 4）
    EAST_EUROPE,   // 东欧 / 俄罗斯（波次 5）
    EXCLUDED       // 中国与发达国家：暂不做
}
