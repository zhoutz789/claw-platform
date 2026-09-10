package com.claw.server.common.enums;

/**
 * 任务类型（对应 claw.tasks.task_type）。
 *
 * <p>P0 仅 LOGISTICS 走完整闭环；DRONE_OP / HAIL_RIDE / TAXI / AD 仅落库与枚举，
 * 业务逻辑后续阶段接入。
 */
public enum TaskType {
    /** 无人机作业。 */
    DRONE_OP,
    /** 物流配送。 */
    LOGISTICS,
    /** 网约车（顺风车/快车）。 */
    HAIL_RIDE,
    /** 出租车。 */
    TAXI,
    /** 广告投放。 */
    AD
}
