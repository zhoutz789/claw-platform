package com.claw.server.common.enums;

/**
 * 无人机作业场景（机型产品类的 {@code scenario} 维度）。
 * 与 {@code drone_product_classes.scenario} 列取值对应，配置驱动扩展机型即按此场景归类。
 */
public enum DroneScenario {
    /** 农业植保。 */
    AGRICULTURE,
    /** 电力 / 基建巡检。 */
    INSPECTION,
    /** 低空物流。 */
    LOGISTICS,
    /** 应急救援。 */
    RESCUE,
    /** 测绘勘察（含排雷 / UXO 仅勘察）。 */
    SURVEY,
    /** 巡逻安保。 */
    PATROL,
    /** 遥感监测（含边境仅感知层）。 */
    MONITOR
}
