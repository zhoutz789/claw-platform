package com.claw.server.common.enums;

/**
 * 容量类型（对应 V71 capacity_plans.capacity_type，P0-2 必须先行定类）。
 * SERIAL   ：串行产能（车辆等一次仅一人用）——定购单位代表"优先权 + 运营回佣资格"，非真实可并行使用时间。
 * PARALLEL ：并行产能（无人机机队/充电桩多枪等）——定购单位代表真实可并行使用的额度。
 */
public enum CapacityType {
    SERIAL,
    PARALLEL
}
