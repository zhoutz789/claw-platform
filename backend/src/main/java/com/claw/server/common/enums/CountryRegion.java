package com.claw.server.common.enums;

/** 地理分区（与 countries.region 一致）。覆盖全球，无排除分区。 */
public enum CountryRegion {
    SEA,           // 东南亚
    WEST_ASIA,     // 西亚
    AFRICA,        // 非洲
    EAST_EUROPE,   // 东欧 / 俄罗斯
    EAST_ASIA,     // 东亚（中国 / 日本 / 韩国等）
    NORTH_AMERICA, // 北美
    EUROPE,        // 欧洲（含欧盟）
    OCEANIA        // 大洋洲
}
