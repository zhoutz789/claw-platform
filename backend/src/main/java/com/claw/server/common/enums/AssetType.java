package com.claw.server.common.enums;

/** 资产类型（对应 assets.asset_type）。 */
public enum AssetType {
    VEHICLE,
    EV,
    BATTERY,
    CHARGER,
    PV_STATION,
    DRONE,
    /** 储能（ESS）：独立权限/菜单、单独管理、不流入换电市场；与 BATTERY 互斥。 */
    ENERGY_STORAGE
}
