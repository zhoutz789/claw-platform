package com.claw.server.common.enums;

/**
 * 飞手档案状态（对应 claw.pilot_profile.status）。
 * 审核闭环：PENDING（待审）→ ACTIVE（通过）；ACTIVE 可被 SUSPENDED（停用）或 BANNED（吊销）。
 */
public enum PilotStatus {
    /** 已提交待审核。 */
    PENDING,
    /** 审核通过，可接单作业。 */
    ACTIVE,
    /** 暂停（违规处罚等临时停用）。 */
    SUSPENDED,
    /** 吊销（严重违规，永久禁用）。 */
    BANNED
}
