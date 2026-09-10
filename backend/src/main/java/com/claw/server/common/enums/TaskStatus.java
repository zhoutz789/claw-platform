package com.claw.server.common.enums;

/**
 * 任务状态机（对应 claw.tasks.status）。
 *
 * <pre>
 * OPEN(发布) → ASSIGNED(接单绑资产) → IN_PROGRESS(进度上报)
 *   → COMPLETED(完成) → SETTLED(结算入账)
 * 任意态 → CANCELLED(取消) / DISPUTED(争议)
 * </pre>
 */
public enum TaskStatus {
    /** 已发布，等待接单。 */
    OPEN,
    /** 已接单（资产已绑定）。 */
    ASSIGNED,
    /** 进行中（已有进度上报）。 */
    IN_PROGRESS,
    /** 已完成（待结算）。 */
    COMPLETED,
    /** 已结算（账本入账完成，终态）。 */
    SETTLED,
    /** 已取消（终态）。 */
    CANCELLED,
    /** 争议中（人工介入）。 */
    DISPUTED;

    /** 终态判断：SETTLED / CANCELLED 不再可流转。 */
    public boolean isTerminal() {
        return this == SETTLED || this == CANCELLED;
    }
}
