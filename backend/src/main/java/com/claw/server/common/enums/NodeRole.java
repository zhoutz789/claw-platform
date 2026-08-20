package com.claw.server.common.enums;

/** 国家在全球网络中的节点角色（与 countries.node_role 一致）。 */
public enum NodeRole {
    OPERATOR, // 平台自营运营节点：资产/换电/分期/人人经济直接落地
    SUPPLIER, // 商品与资产供给节点：向全球供给车辆/电池/光伏设备
    MARKET,   // 商品/服务消费市场与商家网络节点
    HUB       // 兼具供给与市场（跨境枢纽，如中国）
}
